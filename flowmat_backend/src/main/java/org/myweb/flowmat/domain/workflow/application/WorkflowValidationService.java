package org.myweb.flowmat.domain.workflow.application;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

/**
 * Checks a workflow's draft graph. Each issue carries its evidence, the edits that clear it by this check's own rules (never
 * general advice) and the derived warnings it explains: a broken connection is left out of the graph, so the warnings that
 * only its absence causes (an isolated process, a start or end where none is expected, a gateway with one route) are not
 * reported separately but listed as hidden by its error. Only warnings are ever hidden, so the error count, and with it
 * whether publishing is refused, does not depend on this.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowValidationService {
    private static final String LIVE = "N";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /**
     * Where a workflow may start and end once it says so: a workflow with an Input node marks where material enters, so any
     * other process without an incoming connection is unexpected; Output nodes do the same for where results leave. Storage
     * may hold opening or finished stock, so it may be either. As with archify's allowedRoots and allowedTerminals, nothing is
     * checked without that declaration, so workflows of plain processes (a data flow, say) are left alone.
     */
    private static final List<String> ROOT_KINDS = List.of("input", "storage");
    private static final List<String> TERMINAL_KINDS = List.of("output", "storage");
    /** Gateways already report a missing incoming connection themselves. */
    private static final Set<String> GATEWAYS = Set.of("exclusive", "join", "split");

    private final WorkflowAccessService workflowAccess;
    private final ProcessRepository processes;
    private final ProcessIoRepository ports;
    private final ProcessConnectionRepository connections;
    private final ProcessConnectionServiceImpl connectionService;
    private final CatalogQuery catalog;

    public WorkflowValidationResponse validate(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        List<Process> activeProcesses = processes.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, LIVE);
        Map<String, Process> processById = new LinkedHashMap<>();
        for (Process process : activeProcesses) processById.put(process.getProcessId(), process);
        Set<String> processIds = new LinkedHashSet<>(processById.keySet());
        List<ProcessIo> activePorts = processIds.isEmpty() ? List.of()
            : ports.findAllByProcessIdInAndDeletedYnOrderByCreatedAtAsc(processIds, LIVE);
        Map<String, ProcessIo> portById = new HashMap<>();
        for (ProcessIo port : activePorts) portById.put(port.getProcessIoId(), port);
        List<ProcessConnection> activeConnections = connections
            .findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, LIVE);
        Names names = new Names(processById);
        List<Draft> issues = new ArrayList<>();
        Set<String> connectedPorts = new HashSet<>();
        Links links = new Links();
        List<ProcessConnection> usableConnections = new ArrayList<>();

        for (ProcessIo port : activePorts) {
            try {
                ProcessIoServiceImpl.validatePortValues(port);
            } catch (BusinessException exception) {
                issues.add(issue("error", "PORT_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                    exception.getMessage())
                    .with("direction", port.getDirection()).with("quantity", port.getQuantity()).with("unit", port.getUnit())
                    .with("requiredYn", port.getRequiredYn()).with("allowShortageYn", port.getAllowShortageYn()));
                continue;
            }
            PortSchema schema;
            try {
                schema = PortSchema.parseStored(port.getSchemaJson());
            } catch (BusinessException exception) {
                issues.add(issue("error", "PORT_SCHEMA_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                    exception.getMessage()).with("field", "schemaJson"));
                continue;
            }
            if (text(port.getValidationRule())) {
                try {
                    ConditionExpression expression = ConditionExpression.compile(port.getValidationRule());
                    expression.requireDeclaredAttributes(schema == null ? null : schema.properties().keySet());
                } catch (BusinessException exception) {
                    issues.add(issue("error", "EXPRESSION_INVALID", port.getProcessId(), port.getProcessIoId(), null,
                        exception.getMessage())
                        .with("field", "validationRule").with("expression", port.getValidationRule())
                        .with("declaredAttributes", attributes(schema)));
                }
            }
            if (unknownUnit(port.getUnit())) {
                issues.add(issue("warning", "UNIT_UNKNOWN", port.getProcessId(), port.getProcessIoId(), null,
                    "Port unit '" + port.getUnit() + "' is not in unit_master.")
                    .with("field", "unit").with("unit", port.getUnit()));
            }
            if (!ResourceTypes.isStandard(port.getResourceType())) {
                issues.add(issue("warning", "RESOURCE_TYPE_UNKNOWN", port.getProcessId(), port.getProcessIoId(), null,
                    "Port resourceType '" + port.getResourceType() + "' is not a standard resource type.")
                    .with("field", "resourceType").with("resourceType", port.getResourceType())
                    .with("standardTypes", new TreeSet<>(ResourceTypes.STANDARD)));
            }
        }

        for (ProcessConnection connection : activeConnections) {
            String id = connection.getConnectionId();
            ProcessIo source = portById.get(connection.getFromIoId());
            ProcessIo target = portById.get(connection.getToIoId());
            List<String> missing = new ArrayList<>();
            if (!processIds.contains(connection.getFromProcessId())) missing.add("fromProcess");
            if (!processIds.contains(connection.getToProcessId())) missing.add("toProcess");
            if (connection.getFromIoId() != null && source == null) missing.add("fromPort");
            if (connection.getToIoId() != null && target == null) missing.add("toPort");
            if (!missing.isEmpty()) {
                links.broken(connection, add(issues, issue("error", "CONNECTION_ORPHAN", null, null, id,
                    "Connection " + id + " points to a deleted process or port.")
                    .with("missing", missing).ends(connection)
                    .fix("delete " + names.connection(connection))));
                continue;
            }
            if (text(connection.getConditionExpr())) {
                PortSchema schema = null;
                try {
                    ConditionExpression expression = ConditionExpression.compile(connection.getConditionExpr());
                    schema = source == null ? null : PortSchema.parseStored(source.getSchemaJson());
                    expression.requireDeclaredAttributes(schema == null ? null : schema.properties().keySet());
                } catch (BusinessException exception) {
                    links.broken(connection, add(issues, issue("error", "EXPRESSION_INVALID", null, null, id,
                        exception.getMessage())
                        .with("field", "conditionExpr").with("expression", connection.getConditionExpr())
                        .with("sourceAttributes", attributes(schema)).ends(connection)));
                    continue;
                }
            }
            try {
                connectionService.validateConnectionContract(connection, id);
            } catch (BusinessException exception) {
                links.broken(connection, add(issues, issue("error", "CONNECTION_INCOMPATIBLE", null, null, id,
                    exception.getMessage()).ends(connection)));
                continue;
            }
            usableConnections.add(connection);
            links.usable(connection);
            if (connection.getFromIoId() != null) connectedPorts.add(connection.getFromIoId());
            if (connection.getToIoId() != null) connectedPorts.add(connection.getToIoId());
            if (source != null && target != null
                && (source.getSchemaJson() == null || target.getSchemaJson() == null)) {
                issues.add(issue("warning", "SCHEMA_UNVERIFIED", null, null, id,
                    "Connection " + id + " has a port without schemaJson.")
                    .with("sourceHasSchema", source.getSchemaJson() != null)
                    .with("targetHasSchema", target.getSchemaJson() != null).ends(connection));
            }
            if (unknownUnit(connection.getUnit())) {
                issues.add(issue("warning", "UNIT_UNKNOWN", null, null, id,
                    "Connection unit '" + connection.getUnit() + "' is not in unit_master.")
                    .with("field", "unit").with("unit", connection.getUnit()));
            }
        }

        for (ProcessIo port : activePorts) {
            if ("input".equalsIgnoreCase(port.getDirection()) && "Y".equalsIgnoreCase(port.getRequiredYn())
                && !connectedPorts.contains(port.getProcessIoId())) {
                issues.add(issue("error", "REQUIRED_INPUT_UNCONNECTED", port.getProcessId(),
                    port.getProcessIoId(), null, "Required input port " + port.getProcessIoId() + " has no connection.")
                    .with("requiredYn", port.getRequiredYn()).with("usableConnections", 0)
                    .fix("connect an output to input " + names.port(port))
                    .fix("make input " + names.port(port) + " optional"));
            }
        }
        Map<String, Draft> isolated = new HashMap<>();
        for (Process process : activeProcesses) {
            String id = process.getProcessId();
            if (links.in(id) + links.out(id) > 0) continue;
            Draft cause = links.firstBroken(id);
            if (cause != null) {
                cause.suppress("PROCESS_ISOLATED");
                continue;
            }
            isolated.put(id, add(issues, issue("warning", "PROCESS_ISOLATED", id, null, null,
                "Process " + id + " has no connection.")
                .with("incoming", 0).with("outgoing", 0)
                .fix("connect " + names.process(id) + " to the step before or after it")));
        }
        endpointIssues(activeProcesses, links, isolated, names, issues);
        gatewayIssues(activeProcesses, usableConnections, links, names, issues);
        List<String> cycle = cycleMembers(processIds, usableConnections);
        if (!cycle.isEmpty()) {
            issues.add(issue("warning", "CYCLE", null, null, null,
                "Workflow contains a directed cycle; automatic execution must define loop semantics.")
                .with("processIds", cycle));
        }
        List<WorkflowValidationIssue> sorted = issues.stream()
            .map(Draft::build)
            .sorted(Comparator.comparing((WorkflowValidationIssue issue) -> !"error".equals(issue.severity())))
            .toList();
        int errors = (int) sorted.stream().filter(issue -> issue.severity().equals("error")).count();
        return new WorkflowValidationResponse(errors, sorted.size() - errors, sorted);
    }

    /**
     * Starts and ends where the workflow does not expect them ({@link #ROOT_KINDS}): a process the isolation warning or a
     * broken connection already explains is listed as hidden by that issue instead.
     */
    private static void endpointIssues(List<Process> processes, Links links, Map<String, Draft> isolated, Names names,
        List<Draft> issues) {
        long inputs = processes.stream().filter(process -> "input".equals(kind(process))).count();
        long outputs = processes.stream().filter(process -> "output".equals(kind(process))).count();
        for (Process process : processes) {
            String id = process.getProcessId();
            String kind = kind(process);
            if (inputs > 0 && links.in(id) == 0 && !ROOT_KINDS.contains(kind) && !GATEWAYS.contains(kind)) {
                Draft cause = links.firstBrokenIn(id);
                if (cause == null) cause = isolated.get(id);
                if (cause != null) cause.suppress("UNEXPECTED_ROOT");
                else issues.add(issue("warning", "UNEXPECTED_ROOT", id, null, null,
                    "Process " + id + " has no incoming connection, but this workflow marks where material enters with Input"
                        + " nodes.")
                    .with("nodeType", kind).with("incoming", 0).with("inputNodes", inputs)
                    .with("allowedRootKinds", ROOT_KINDS)
                    .fix("connect an earlier step into " + names.process(id))
                    .fix("change " + names.process(id) + " to an Input node if material enters the workflow there"));
            }
            if (outputs > 0 && links.out(id) == 0 && !TERMINAL_KINDS.contains(kind)
                && !"exclusive".equals(kind) && !"split".equals(kind)) {
                Draft cause = links.firstBrokenOut(id);
                if (cause == null) cause = isolated.get(id);
                if (cause != null) cause.suppress("UNEXPECTED_TERMINAL");
                else issues.add(issue("warning", "UNEXPECTED_TERMINAL", id, null, null,
                    "Process " + id + " has no outgoing connection, but this workflow marks where results leave with Output"
                        + " nodes.")
                    .with("nodeType", kind).with("outgoing", 0).with("outputNodes", outputs)
                    .with("allowedTerminalKinds", TERMINAL_KINDS)
                    .fix("connect " + names.process(id) + " to a later step")
                    .fix("change " + names.process(id) + " to an Output node if results leave the workflow there"));
            }
        }
    }

    /**
     * Exclusive gateways (docs/architecture/adr/ADR-006-operation-types.md): at most one default connection each, and only
     * they have one; each needs an incoming connection, since it passes on what an earlier step gives it. A Join
     * (docs/architecture/adr/ADR-007-join-gateway.md decision 10) needs an incoming connection and waits for nothing with one.
     */
    private static void gatewayIssues(List<Process> processes, List<ProcessConnection> connections, Links links, Names names,
        List<Draft> issues) {
        Set<String> gateways = new LinkedHashSet<>();
        Set<String> splits = new LinkedHashSet<>();
        for (Process process : processes) {
            if ("exclusive".equals(kind(process))) gateways.add(process.getProcessId());
            if ("split".equals(kind(process))) splits.add(process.getProcessId());
        }
        Map<String, List<ProcessConnection>> outgoing = new HashMap<>();
        for (ProcessConnection connection : connections) {
            String id = connection.getConnectionId();
            String from = connection.getFromProcessId();
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(connection);
            if (connection.isDefaultRoute() && !gateways.contains(from)) {
                issues.add(issue("error", "DEFAULT_ROUTE_OUTSIDE_GATEWAY", null, null, id,
                    "Connection " + id + " is a default connection, but only an Exclusive gateway has one.")
                    .with("field", "defaultRouteYn").with("fromNodeType", names.kind(from)).ends(connection)
                    .fix("clear \"default\" on " + names.connection(connection)));
            }
            if (connection.getSplitKind() != null && !splits.contains(from)) {
                issues.add(issue("error", "SPLIT_OUTSIDE_GATEWAY", null, null, id,
                    "Connection " + id + " has a split share, but only a Split gateway divides its output.")
                    .with("field", "splitKind").with("splitKind", connection.getSplitKind())
                    .with("fromNodeType", names.kind(from)).ends(connection)
                    .fix("clear the split share on " + names.connection(connection)));
            }
            BigDecimal lossRate = connection.getLossRate();
            if (lossRate != null && (lossRate.signum() < 0 || lossRate.compareTo(BigDecimal.ONE) >= 0)) {
                Draft loss = add(issues, issue("error", "LOSS_RATE_RANGE", null, null, id,
                    "Connection " + id + " has loss rate " + plain(lossRate)
                        + "; a loss rate is a fraction of at least 0 and below 1 (ADR-009).")
                    .with("field", "lossRate").with("lossRate", lossRate).with("atLeast", 0).with("below", 1));
                BigDecimal fraction = lossFraction(lossRate);
                if (fraction != null) {
                    loss.fix("set the loss rate on " + names.connection(connection) + " to " + plain(fraction)
                        + (lossRate.signum() > 0 ? " (" + plain(lossRate) + "% as a fraction)" : ""));
                }
            }
            BigDecimal delay = connection.getDelayTimeSec();
            if (delay != null && delay.signum() < 0) {
                issues.add(issue("error", "DELAY_RANGE", null, null, id,
                    "Connection " + id + " has delay " + plain(delay) + " s; a delay is 0 or more seconds (ADR-010).")
                    .with("field", "delayTimeSec").with("delaySeconds", delay).with("atLeast", 0)
                    .fix("set the delay on " + names.connection(connection) + " to " + plain(delay.negate()) + " s"));
            }
        }
        splitIssues(splits, outgoing, links, names, issues);
        for (Process process : processes) {
            if (!"join".equals(kind(process))) {
                continue;
            }
            String join = process.getProcessId();
            int count = links.in(join);
            if (count == 0) {
                issues.add(issue("error", "JOIN_WITHOUT_INPUT", join, null, null,
                    "Join " + join + " has no incoming connection; it waits for the branches that lead into it.")
                    .with("incoming", 0)
                    .fix("connect the branches that lead into " + names.process(join)));
            } else if (count == 1) {
                Draft cause = count + links.brokenIn(join) >= 2 ? links.firstBrokenIn(join) : null;
                if (cause != null) cause.suppress("JOIN_SINGLE_INPUT");
                else issues.add(issue("warning", "JOIN_SINGLE_INPUT", join, null, null,
                    "Join " + join + " has one incoming connection, so it has nothing to wait for.")
                    .with("incoming", 1)
                    .fix("connect another branch into " + names.process(join)));
            }
        }
        for (String gateway : gateways) {
            List<ProcessConnection> out = outgoing.getOrDefault(gateway, List.of());
            List<String> defaults = out.stream().filter(ProcessConnection::isDefaultRoute)
                .map(ProcessConnection::getConnectionId).toList();
            if (defaults.size() > 1) {
                issues.add(issue("error", "GATEWAY_DEFAULTS", gateway, null, null,
                    "Exclusive gateway " + gateway + " has " + defaults.size() + " default connections; keep at most one.")
                    .with("defaultConnections", defaults));
            }
            if (links.in(gateway) == 0) {
                issues.add(issue("error", "GATEWAY_WITHOUT_INPUT", gateway, null, null,
                    "Exclusive gateway " + gateway + " has no incoming connection; it passes on what an earlier step gives it.")
                    .with("incoming", 0)
                    .fix("connect an earlier step into " + names.process(gateway)));
            }
            if (out.size() < 2) {
                singleRoute(issues, links, gateway, out.size(), "GATEWAY_SINGLE_ROUTE",
                    "Exclusive gateway " + gateway + " has fewer than two outgoing connections, so it has nothing to choose.",
                    "connect " + names.process(gateway) + (out.isEmpty() ? " to two later steps" : " to one more later step"));
            }
            Map<Integer, List<String>> byPriority = new TreeMap<>();
            for (ProcessConnection connection : out) {
                if (connection.isDefaultRoute()) {
                    if (text(connection.getConditionExpr())) {
                        issues.add(issue("warning", "DEFAULT_ROUTE_CONDITION_IGNORED", null, null, connection.getConnectionId(),
                            "Default connection " + connection.getConnectionId() + " is taken only when no other connection's "
                                + "condition holds, so its own condition is not used.")
                            .with("field", "conditionExpr").with("expression", connection.getConditionExpr())
                            .fix("clear the condition on " + names.connection(connection)));
                    }
                    continue;
                }
                int priority = connection.getPriority() == null ? 0 : connection.getPriority();
                byPriority.computeIfAbsent(priority, ignored -> new ArrayList<>()).add(connection.getConnectionId());
            }
            // Renumbering would clear a tie but could change which route is tried first, so no fix is offered.
            byPriority.forEach((priority, tied) -> {
                if (tied.size() < 2) return;
                issues.add(issue("warning", "GATEWAY_PRIORITY_TIE", gateway, null, null,
                    "Exclusive gateway " + gateway + " has more than one connection with priority " + priority
                        + "; the one drawn first is tried first.")
                    .with("priority", priority).with("connections", tied));
            });
        }
    }

    /**
     * Split gateways (docs/architecture/adr/ADR-008-quantity-split.md decision 9): each needs an incoming connection and a share
     * on every outgoing one, at most one rest, percents of at most 100 and, without a rest, percents of exactly 100 and no
     * fixed quantities; no conditions, since a Split divides what it gets. How to re-divide shares is the author's call, so
     * those issues offer no fix.
     */
    private static void splitIssues(Set<String> splits, Map<String, List<ProcessConnection>> outgoing, Links links,
        Names names, List<Draft> issues) {
        for (String split : splits) {
            List<ProcessConnection> out = outgoing.getOrDefault(split, List.of());
            if (links.in(split) == 0) {
                issues.add(issue("error", "SPLIT_WITHOUT_INPUT", split, null, null,
                    "Split gateway " + split + " has no incoming connection; it divides what an earlier step gives it.")
                    .with("incoming", 0)
                    .fix("connect an earlier step into " + names.process(split)));
            }
            if (out.size() < 2) {
                singleRoute(issues, links, split, out.size(), "SPLIT_SINGLE_ROUTE",
                    "Split gateway " + split + " has fewer than two outgoing connections, so it has nothing to divide between.",
                    "connect " + names.process(split) + (out.isEmpty() ? " to two later steps" : " to one more later step")
                        + " with a share");
            }
            List<String> rests = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            boolean fixed = false;
            BigDecimal shares = BigDecimal.ZERO;
            for (ProcessConnection connection : out) {
                String kind = connection.getSplitKind();
                if (kind == null) {
                    missing.add(connection.getConnectionId());
                } else if ("rest".equals(kind)) {
                    rests.add(connection.getConnectionId());
                } else if ("fixed".equals(kind)) {
                    fixed = true;
                } else if (connection.getSplitValue() != null) {
                    shares = shares.add(connection.getSplitValue());
                }
            }
            for (ProcessConnection connection : out) {
                if (connection.getSplitKind() == null) {
                    Draft share = add(issues, issue("error", "SPLIT_SHARE_MISSING", null, null, connection.getConnectionId(),
                        "Connection " + connection.getConnectionId() + " leaves Split gateway " + split
                            + " without a share; give it a percent, a fixed quantity or the rest.")
                        .with("field", "splitKind").with("splitProcessId", split));
                    // Only when it would be the one rest; with a rest already there, the share is the author's call.
                    if (rests.isEmpty() && missing.size() == 1) share.fix("make " + names.connection(connection) + " the rest");
                }
                if (text(connection.getConditionExpr())) {
                    issues.add(issue("error", "SPLIT_CONDITION", null, null, connection.getConnectionId(),
                        "Connection " + connection.getConnectionId() + " leaves Split gateway " + split
                            + " with a condition; a Split divides what it gets without conditions.")
                        .with("field", "conditionExpr").with("expression", connection.getConditionExpr())
                        .fix("clear the condition on " + names.connection(connection)));
                }
            }
            if (rests.size() > 1) {
                issues.add(issue("error", "SPLIT_REST_DUPLICATE", split, null, null,
                    "Split gateway " + split + " has " + rests.size() + " rest connections; keep at most one.")
                    .with("restConnections", rests));
            }
            if (shares.compareTo(HUNDRED) > 0) {
                issues.add(issue("error", "SPLIT_SHARES_OVER", split, null, null,
                    "Split gateway " + split + " shares out " + plain(shares) + "%, more than 100%.")
                    .with("percentTotal", shares));
            } else if (rests.isEmpty() && missing.isEmpty() && !out.isEmpty()
                && (fixed || shares.compareTo(HUNDRED) != 0)) {
                issues.add(issue("error", "SPLIT_NO_REST", split, null, null,
                    "Split gateway " + split + " needs a rest connection: without one, its percents must add up to exactly 100"
                        + " and it cannot have fixed quantities.")
                    .with("percentTotal", shares).with("fixedShares", fixed));
            }
        }
    }

    /** A gateway with fewer than two routes, unless a broken outgoing connection is why: then that error hides it. */
    private static void singleRoute(List<Draft> issues, Links links, String gateway, int routes, String code, String message,
        String fix) {
        Draft cause = routes + links.brokenOut(gateway) >= 2 ? links.firstBrokenOut(gateway) : null;
        if (cause != null) {
            cause.suppress(code);
            return;
        }
        issues.add(issue("warning", code, gateway, null, null, message).with("outgoing", routes).fix(fix));
    }

    /** A loss rate typed as a percent (1 to below 100) or with a stray minus sign, as the fraction it would be; else none. */
    private static BigDecimal lossFraction(BigDecimal rate) {
        if (rate.signum() < 0 && rate.negate().compareTo(BigDecimal.ONE) < 0) return rate.negate();
        if (rate.compareTo(BigDecimal.ONE) >= 0 && rate.compareTo(HUNDRED) < 0) return rate.movePointLeft(2);
        return null;
    }

    private boolean unknownUnit(String unit) {
        return text(unit) && !catalog.isKnownUnitCode(unit);
    }

    /**
     * The processes on a directed cycle, in creation order; empty when there is none. Processes the topological sort cannot
     * place lie on a cycle or after one, so only those are checked for a way back to themselves.
     */
    private static List<String> cycleMembers(Set<String> processIds, List<ProcessConnection> connections) {
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
        Set<String> placed = new HashSet<>();
        while (!ready.isEmpty()) {
            String current = ready.remove();
            placed.add(current);
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int degree = indegrees.computeIfPresent(next, (ignored, value) -> value - 1);
                if (degree == 0) ready.add(next);
            }
        }
        if (placed.size() == processIds.size()) return List.of();
        List<String> members = new ArrayList<>();
        for (String id : processIds) {
            if (!placed.contains(id) && reachesItself(outgoing, id)) members.add(id);
        }
        return members;
    }

    private static boolean reachesItself(Map<String, List<String>> outgoing, String start) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(outgoing.getOrDefault(start, List.of()));
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (current.equals(start)) return true;
            if (seen.add(current)) pending.addAll(outgoing.getOrDefault(current, List.of()));
        }
        return false;
    }

    /** The stored node type as the palette reads it: blank is a process and the old "decision" is an output. */
    private static String kind(Process process) {
        String type = process.getNodeType();
        if (!text(type)) return "process";
        String lower = type.trim().toLowerCase(Locale.ROOT);
        return "decision".equals(lower) ? "output" : lower;
    }

    private static TreeSet<String> attributes(PortSchema schema) {
        return schema == null ? null : new TreeSet<>(schema.properties().keySet());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Draft issue(String severity, String code, String processId, String ioId, String connectionId,
        String message) {
        return new Draft(severity, code, processId, ioId, connectionId, message);
    }

    private static Draft add(List<Draft> issues, Draft draft) {
        issues.add(draft);
        return draft;
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }

    /** How a fix names what to edit: by the names people gave, falling back to IDs. */
    private record Names(Map<String, Process> processes) {
        String process(String id) {
            Process process = processes.get(id);
            return "\"" + (process == null || !text(process.getProcessName()) ? id : process.getProcessName()) + "\"";
        }

        String kind(String id) {
            Process process = processes.get(id);
            return process == null ? null : WorkflowValidationService.kind(process);
        }

        String port(ProcessIo port) {
            String label = text(port.getIoName()) ? port.getIoName()
                : text(port.getItemId()) ? port.getItemId() : port.getProcessIoId();
            return "port \"" + label + "\" of " + process(port.getProcessId());
        }

        String connection(ProcessConnection connection) {
            return "the connection from " + process(connection.getFromProcessId()) + " to "
                + process(connection.getToProcessId());
        }
    }

    /** Per process, the connections that count and the broken ones, whose errors explain what a missing connection causes. */
    private static final class Links {
        private final Map<String, Integer> incoming = new HashMap<>();
        private final Map<String, Integer> outgoing = new HashMap<>();
        private final Map<String, List<Draft>> brokenIncoming = new HashMap<>();
        private final Map<String, List<Draft>> brokenOutgoing = new HashMap<>();

        void usable(ProcessConnection connection) {
            outgoing.merge(connection.getFromProcessId(), 1, Integer::sum);
            incoming.merge(connection.getToProcessId(), 1, Integer::sum);
        }

        void broken(ProcessConnection connection, Draft cause) {
            brokenOutgoing.computeIfAbsent(connection.getFromProcessId(), ignored -> new ArrayList<>()).add(cause);
            brokenIncoming.computeIfAbsent(connection.getToProcessId(), ignored -> new ArrayList<>()).add(cause);
        }

        int in(String processId) { return incoming.getOrDefault(processId, 0); }

        int out(String processId) { return outgoing.getOrDefault(processId, 0); }

        int brokenIn(String processId) { return brokenIncoming.getOrDefault(processId, List.of()).size(); }

        int brokenOut(String processId) { return brokenOutgoing.getOrDefault(processId, List.of()).size(); }

        Draft firstBrokenIn(String processId) {
            List<Draft> causes = brokenIncoming.get(processId);
            return causes == null ? null : causes.get(0);
        }

        Draft firstBrokenOut(String processId) {
            List<Draft> causes = brokenOutgoing.get(processId);
            return causes == null ? null : causes.get(0);
        }

        Draft firstBroken(String processId) {
            Draft cause = firstBrokenIn(processId);
            return cause != null ? cause : firstBrokenOut(processId);
        }
    }

    /** An issue while the check runs: a cause found later can still add the warnings it hides. */
    private static final class Draft {
        private final String severity;
        private final String code;
        private final String processId;
        private final String ioId;
        private final String connectionId;
        private final String message;
        private final Map<String, Object> evidence = new LinkedHashMap<>();
        private final List<String> fixes = new ArrayList<>();
        private final Set<String> hidden = new LinkedHashSet<>();

        Draft(String severity, String code, String processId, String ioId, String connectionId, String message) {
            this.severity = severity;
            this.code = code;
            this.processId = processId;
            this.ioId = ioId;
            this.connectionId = connectionId;
            this.message = message;
        }

        Draft with(String key, Object value) {
            if (value != null) evidence.put(key, value);
            return this;
        }

        Draft ends(ProcessConnection connection) {
            return with("fromProcessId", connection.getFromProcessId()).with("toProcessId", connection.getToProcessId())
                .with("fromIoId", connection.getFromIoId()).with("toIoId", connection.getToIoId());
        }

        Draft fix(String edit) {
            fixes.add(edit);
            return this;
        }

        void suppress(String hiddenCode) {
            hidden.add(hiddenCode);
        }

        WorkflowValidationIssue build() {
            return new WorkflowValidationIssue(severity, code, processId, ioId, connectionId, message, evidence, fixes,
                List.copyOf(hidden));
        }
    }
}
