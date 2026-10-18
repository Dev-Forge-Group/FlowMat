package org.myweb.flowmat.domain.workflow.application;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowValidationIssue;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowValidationResponse;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.contract.ResourceTypes;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowValidationService {
    private static final String LIVE = "N";

    private final WorkflowAccessService workflowAccess;
    private final ProcessRepository processes;
    private final ProcessIoRepository ports;
    private final ProcessConnectionRepository connections;
    private final ProcessConnectionServiceImpl connectionService;
    private final CatalogQuery catalog;

    public WorkflowValidationResponse validate(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        List<Process> activeProcesses = processes.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, LIVE);
        Set<String> processIds = new HashSet<>();
        for (Process process : activeProcesses) processIds.add(process.getProcessId());
        List<ProcessIo> activePorts = processIds.isEmpty() ? List.of()
            : ports.findAllByProcessIdInAndDeletedYnOrderByCreatedAtAsc(processIds, LIVE);
        Map<String, ProcessIo> portById = new HashMap<>();
        for (ProcessIo port : activePorts) portById.put(port.getProcessIoId(), port);
        List<ProcessConnection> activeConnections = connections
            .findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, LIVE);
        List<WorkflowValidationIssue> issues = new ArrayList<>();
        Set<String> connectedPorts = new HashSet<>();
        Set<String> connectedProcesses = new HashSet<>();
        List<ProcessConnection> usableConnections = new ArrayList<>();

        for (ProcessIo port : activePorts) {
            try {
                ProcessIoServiceImpl.validatePortValues(port);
            } catch (BusinessException exception) {
                issues.add(issue("error", "PORT_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                    exception.getMessage()));
                continue;
            }
            PortSchema schema;
            try {
                schema = PortSchema.parseStored(port.getSchemaJson());
            } catch (BusinessException exception) {
                issues.add(issue("error", "PORT_SCHEMA_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                    exception.getMessage()));
                continue;
            }
            if (text(port.getValidationRule())) {
                try {
                    ConditionExpression expression = ConditionExpression.compile(port.getValidationRule());
                    expression.requireDeclaredAttributes(schema == null ? null : schema.properties().keySet());
                } catch (BusinessException exception) {
                    issues.add(issue("error", "EXPRESSION_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                        exception.getMessage()));
                }
            }
            if (unknownUnit(port.getUnit())) {
                issues.add(issue("warning", "UNIT_UNKNOWN", port.getProcessId(), port.getProcessIoId(), null,
                    "Port unit '" + port.getUnit() + "' is not in unit_master."));
            }
            if (!ResourceTypes.isStandard(port.getResourceType())) {
                issues.add(issue("warning", "RESOURCE_TYPE_UNKNOWN", port.getProcessId(), port.getProcessIoId(), null,
                    "Port resourceType '" + port.getResourceType() + "' is not a standard resource type."));
            }
        }

        for (ProcessConnection connection : activeConnections) {
            String id = connection.getConnectionId();
            ProcessIo source = portById.get(connection.getFromIoId());
            ProcessIo target = portById.get(connection.getToIoId());
            if (!processIds.contains(connection.getFromProcessId())
                || !processIds.contains(connection.getToProcessId())
                || (connection.getFromIoId() != null && source == null)
                || (connection.getToIoId() != null && target == null)) {
                issues.add(issue("error", "CONNECTION_ORPHAN", null, null, id,
                    "Connection " + id + " points to a deleted process or port."));
                continue;
            }
            if (text(connection.getConditionExpr())) {
                try {
                    ConditionExpression expression = ConditionExpression.compile(connection.getConditionExpr());
                    PortSchema schema = source == null ? null : PortSchema.parseStored(source.getSchemaJson());
                    expression.requireDeclaredAttributes(schema == null ? null : schema.properties().keySet());
                } catch (BusinessException exception) {
                    issues.add(issue("error", "EXPRESSION_INVALID", null, null, id, exception.getMessage()));
                    continue;
                }
            }
            try {
                connectionService.validateConnectionContract(connection, id);
            } catch (BusinessException exception) {
                issues.add(issue("error", "CONNECTION_INCOMPATIBLE", null, null, id, exception.getMessage()));
                continue;
            }
            usableConnections.add(connection);
            connectedProcesses.add(connection.getFromProcessId());
            connectedProcesses.add(connection.getToProcessId());
            if (connection.getFromIoId() != null) connectedPorts.add(connection.getFromIoId());
            if (connection.getToIoId() != null) connectedPorts.add(connection.getToIoId());
            if (source != null && target != null
                && (source.getSchemaJson() == null || target.getSchemaJson() == null)) {
                issues.add(issue("warning", "SCHEMA_UNVERIFIED", null, null, id,
                    "Connection " + id + " has a port without schemaJson."));
            }
            if (unknownUnit(connection.getUnit())) {
                issues.add(issue("warning", "UNIT_UNKNOWN", null, null, id,
                    "Connection unit '" + connection.getUnit() + "' is not in unit_master."));
            }
        }

        for (ProcessIo port : activePorts) {
            if ("input".equalsIgnoreCase(port.getDirection()) && "Y".equalsIgnoreCase(port.getRequiredYn())
                && !connectedPorts.contains(port.getProcessIoId())) {
                issues.add(issue("error", "REQUIRED_INPUT_UNCONNECTED", port.getProcessId(),
                    port.getProcessIoId(), null, "Required input port " + port.getProcessIoId() + " has no connection."));
            }
        }
        for (Process process : activeProcesses) {
            if (!connectedProcesses.contains(process.getProcessId())) {
                issues.add(issue("warning", "PROCESS_ISOLATED", process.getProcessId(), null, null,
                    "Process " + process.getProcessId() + " has no connection."));
            }
        }
        gatewayIssues(activeProcesses, usableConnections, issues);
        if (hasCycle(processIds, usableConnections)) {
            issues.add(issue("warning", "CYCLE", null, null, null,
                "Workflow contains a directed cycle; automatic execution must define loop semantics."));
        }
        List<WorkflowValidationIssue> sorted = issues.stream()
            .sorted((first, second) -> first.severity().equals(second.severity()) ? 0
                : first.severity().equals("error") ? -1 : 1)
            .toList();
        int errors = (int) sorted.stream().filter(issue -> issue.severity().equals("error")).count();
        return new WorkflowValidationResponse(errors, sorted.size() - errors, sorted);
    }

    /**
     * Exclusive gateways (docs/architecture/adr/ADR-006-operation-types.md): at most one default connection each, and only
     * they have one; each needs an incoming connection, since it passes on what an earlier step gives it. A Join
     * (docs/architecture/adr/ADR-007-join-gateway.md decision 10) needs an incoming connection and waits for nothing with one.
     */
    private static void gatewayIssues(List<Process> processes, List<ProcessConnection> connections,
        List<WorkflowValidationIssue> issues) {
        Set<String> gateways = new HashSet<>();
        for (Process process : processes) {
            if ("exclusive".equalsIgnoreCase(process.getNodeType())) gateways.add(process.getProcessId());
        }
        Set<String> splits = new HashSet<>();
        for (Process process : processes) {
            if ("split".equalsIgnoreCase(process.getNodeType())) splits.add(process.getProcessId());
        }
        Map<String, List<ProcessConnection>> outgoing = new HashMap<>();
        Set<String> withInput = new HashSet<>();
        Map<String, Integer> inputs = new HashMap<>();
        for (ProcessConnection connection : connections) {
            outgoing.computeIfAbsent(connection.getFromProcessId(), ignored -> new ArrayList<>()).add(connection);
            withInput.add(connection.getToProcessId());
            inputs.merge(connection.getToProcessId(), 1, Integer::sum);
            if (connection.isDefaultRoute() && !gateways.contains(connection.getFromProcessId())) {
                issues.add(issue("error", "DEFAULT_ROUTE_OUTSIDE_GATEWAY", null, null, connection.getConnectionId(),
                    "Connection " + connection.getConnectionId() + " is a default connection, but only an Exclusive gateway has one."));
            }
            if (connection.getSplitKind() != null && !splits.contains(connection.getFromProcessId())) {
                issues.add(issue("error", "SPLIT_OUTSIDE_GATEWAY", null, null, connection.getConnectionId(),
                    "Connection " + connection.getConnectionId() + " has a split share, but only a Split gateway divides its output."));
            }
            BigDecimal lossRate = connection.getLossRate();
            if (lossRate != null && (lossRate.signum() < 0 || lossRate.compareTo(BigDecimal.ONE) >= 0)) {
                issues.add(issue("error", "LOSS_RATE_RANGE", null, null, connection.getConnectionId(),
                    "Connection " + connection.getConnectionId() + " has loss rate " + lossRate.stripTrailingZeros().toPlainString()
                        + "; a loss rate is a fraction of at least 0 and below 1 (ADR-009)."));
            }
            BigDecimal delay = connection.getDelayTimeSec();
            if (delay != null && delay.signum() < 0) {
                issues.add(issue("error", "DELAY_RANGE", null, null, connection.getConnectionId(),
                    "Connection " + connection.getConnectionId() + " has delay " + delay.stripTrailingZeros().toPlainString()
                        + " s; a delay is 0 or more seconds (ADR-010)."));
            }
        }
        splitIssues(splits, outgoing, withInput, issues);
        for (Process process : processes) {
            if (!"join".equalsIgnoreCase(process.getNodeType())) {
                continue;
            }
            String join = process.getProcessId();
            int count = inputs.getOrDefault(join, 0);
            if (count == 0) {
                issues.add(issue("error", "JOIN_WITHOUT_INPUT", join, null, null,
                    "Join " + join + " has no incoming connection; it waits for the branches that lead into it."));
            } else if (count == 1) {
                issues.add(issue("warning", "JOIN_SINGLE_INPUT", join, null, null,
                    "Join " + join + " has one incoming connection, so it has nothing to wait for."));
            }
        }
        for (String gateway : gateways) {
            List<ProcessConnection> out = outgoing.getOrDefault(gateway, List.of());
            long defaults = out.stream().filter(ProcessConnection::isDefaultRoute).count();
            if (defaults > 1) {
                issues.add(issue("error", "GATEWAY_DEFAULTS", gateway, null, null,
                    "Exclusive gateway " + gateway + " has " + defaults + " default connections; keep at most one."));
            }
            if (!withInput.contains(gateway)) {
                issues.add(issue("error", "GATEWAY_WITHOUT_INPUT", gateway, null, null,
                    "Exclusive gateway " + gateway + " has no incoming connection; it passes on what an earlier step gives it."));
            }
            if (out.size() < 2) {
                issues.add(issue("warning", "GATEWAY_SINGLE_ROUTE", gateway, null, null,
                    "Exclusive gateway " + gateway + " has fewer than two outgoing connections, so it has nothing to choose."));
            }
            Set<Integer> seen = new HashSet<>();
            Set<Integer> ties = new TreeSet<>();
            for (ProcessConnection connection : out) {
                if (connection.isDefaultRoute()) {
                    if (text(connection.getConditionExpr())) {
                        issues.add(issue("warning", "DEFAULT_ROUTE_CONDITION_IGNORED", null, null, connection.getConnectionId(),
                            "Default connection " + connection.getConnectionId() + " is taken only when no other connection's "
                                + "condition holds, so its own condition is not used."));
                    }
                    continue;
                }
                int priority = connection.getPriority() == null ? 0 : connection.getPriority();
                if (!seen.add(priority)) ties.add(priority);
            }
            for (Integer priority : ties) {
                issues.add(issue("warning", "GATEWAY_PRIORITY_TIE", gateway, null, null,
                    "Exclusive gateway " + gateway + " has more than one connection with priority " + priority
                        + "; the one drawn first is tried first."));
            }
        }
    }

    /**
     * Split gateways (docs/architecture/adr/ADR-008-quantity-split.md decision 9): each needs an incoming connection and a share
     * on every outgoing one, at most one rest, percents of at most 100 and, without a rest, percents of exactly 100 and no
     * fixed quantities; no conditions, since a Split divides what it gets.
     */
    private static void splitIssues(Set<String> splits, Map<String, List<ProcessConnection>> outgoing, Set<String> withInput,
        List<WorkflowValidationIssue> issues) {
        for (String split : splits) {
            List<ProcessConnection> out = outgoing.getOrDefault(split, List.of());
            if (!withInput.contains(split)) {
                issues.add(issue("error", "SPLIT_WITHOUT_INPUT", split, null, null,
                    "Split gateway " + split + " has no incoming connection; it divides what an earlier step gives it."));
            }
            if (out.size() < 2) {
                issues.add(issue("warning", "SPLIT_SINGLE_ROUTE", split, null, null,
                    "Split gateway " + split + " has fewer than two outgoing connections, so it has nothing to divide between."));
            }
            int rests = 0;
            boolean fixed = false;
            boolean missing = false;
            BigDecimal shares = BigDecimal.ZERO;
            for (ProcessConnection connection : out) {
                String kind = connection.getSplitKind();
                if (kind == null) {
                    missing = true;
                    issues.add(issue("error", "SPLIT_SHARE_MISSING", null, null, connection.getConnectionId(),
                        "Connection " + connection.getConnectionId() + " leaves Split gateway " + split
                            + " without a share; give it a percent, a fixed quantity or the rest."));
                } else if ("rest".equals(kind)) {
                    rests++;
                } else if ("fixed".equals(kind)) {
                    fixed = true;
                } else if (connection.getSplitValue() != null) {
                    shares = shares.add(connection.getSplitValue());
                }
                if (text(connection.getConditionExpr())) {
                    issues.add(issue("error", "SPLIT_CONDITION", null, null, connection.getConnectionId(),
                        "Connection " + connection.getConnectionId() + " leaves Split gateway " + split
                            + " with a condition; a Split divides what it gets without conditions."));
                }
            }
            if (rests > 1) {
                issues.add(issue("error", "SPLIT_REST_DUPLICATE", split, null, null,
                    "Split gateway " + split + " has " + rests + " rest connections; keep at most one."));
            }
            if (shares.compareTo(BigDecimal.valueOf(100)) > 0) {
                issues.add(issue("error", "SPLIT_SHARES_OVER", split, null, null,
                    "Split gateway " + split + " shares out " + shares.stripTrailingZeros().toPlainString() + "%, more than 100%."));
            } else if (rests == 0 && !missing && !out.isEmpty() && (fixed || shares.compareTo(BigDecimal.valueOf(100)) != 0)) {
                issues.add(issue("error", "SPLIT_NO_REST", split, null, null,
                    "Split gateway " + split + " needs a rest connection: without one, its percents must add up to exactly 100"
                        + " and it cannot have fixed quantities."));
            }
        }
    }

    private boolean unknownUnit(String unit) {
        return text(unit) && !catalog.isKnownUnitCode(unit);
    }

    private static boolean hasCycle(Set<String> processIds, List<ProcessConnection> connections) {
        Map<String, Integer> indegrees = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (String id : processIds) indegrees.put(id, 0);
        for (ProcessConnection connection : connections) {
            outgoing.computeIfAbsent(connection.getFromProcessId(), ignored -> new ArrayList<>())
                .add(connection.getToProcessId());
            indegrees.computeIfPresent(connection.getToProcessId(), (ignored, value) -> value + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegrees.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String current = ready.remove();
            visited++;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int degree = indegrees.computeIfPresent(next, (ignored, value) -> value - 1);
                if (degree == 0) ready.add(next);
            }
        }
        return visited < processIds.size();
    }

    private static WorkflowValidationIssue issue(String severity, String code, String processId,
        String ioId, String connectionId, String message) {
        return new WorkflowValidationIssue(severity, code, processId, ioId, connectionId, message);
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
