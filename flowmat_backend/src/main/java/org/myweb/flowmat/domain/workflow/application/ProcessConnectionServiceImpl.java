package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessConnectionResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.contract.WorkflowText;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessConnectionServiceImpl implements ProcessConnectionService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    private static final Set<String> FAILURE_POLICIES = Set.of("stop", "skip", "retry");
    private static final BigDecimal MAX_FLOW_RATE = new BigDecimal("9999999999.9999");
    private static final BigDecimal MAX_DELAY_TIME_SEC = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_LOSS_RATE = new BigDecimal("9.9999");
    private static final BigDecimal MAX_CAPACITY = new BigDecimal("999999999999999.9999");

    private final ProcessConnectionRepository processConnectionRepository;
    private final WorkflowRepository workflowRepository;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final CatalogQuery catalogQuery;
    private final EntityManager entityManager;
    private final IdGenerator idGenerator;
    private final GraphSyncService graphSyncService;
    private final WorkflowAccessService workflowAccess;

    @Override
    public List<ProcessConnectionResponse> listConnections(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return processConnectionRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, NOT_DELETED)
            .stream()
            .map(ProcessConnectionServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProcessConnectionResponse createConnection(ProcessConnectionCreateRequest request) {
        WorkflowText.requireStorable(request.workflowId(), "workflowId");
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(request.workflowId());
        WorkflowText.requireStorable(request.fromProcessId(), "fromProcessId");
        WorkflowText.requireStorable(request.toProcessId(), "toProcessId");
        WorkflowText.requireStorable(request.fromIoId(), "fromIoId");
        WorkflowText.requireStorable(request.toIoId(), "toIoId");
        WorkflowText.requireStorable(request.itemId(), "itemId");
        WorkflowText.requireStorable(request.sourceHandle(), "sourceHandle");
        WorkflowText.requireStorable(request.targetHandle(), "targetHandle");
        WorkflowText.requireStorable(request.connectionType(), "connectionType");
        WorkflowText.requireStorable(request.connectionLabel(), "connectionLabel");
        WorkflowText.requireStorable(request.unit(), "unit");
        WorkflowText.requireStorable(request.conditionExpr(), "conditionExpr");
        lockWorkflow(workflow);
        Process fromProcess = workflowAccess.requireProcessWriteAccess(request.fromProcessId());
        Process toProcess = workflowAccess.requireProcessWriteAccess(request.toProcessId());
        validateProcessMembership(workflow, fromProcess, toProcess);

        ProcessConnection connection = new ProcessConnection();
        connection.setConnectionId(idGenerator.generate());
        connection.setProjectId(workflow.getProjectId());
        connection.setWorkflowId(workflow.getWorkflowId());
        connection.setFromProcessId(fromProcess.getProcessId());
        connection.setToProcessId(toProcess.getProcessId());
        connection.setFromIoId(validateProcessIo(request.fromIoId(), fromProcess.getProcessId(), "output"));
        connection.setToIoId(validateProcessIo(request.toIoId(), toProcess.getProcessId(), "input"));
        connection.setItemId(validateItem(request.itemId(), workflow.getProjectId()));
        connection.setSourceHandle(resolveHandle(request.sourceHandle(), connection.getFromIoId(), "out"));
        connection.setTargetHandle(resolveHandle(request.targetHandle(), connection.getToIoId(), "in"));
        connection.setConnectionType(defaultIfBlank(request.connectionType(), "material"));
        connection.setConnectionLabel(trimToNull(request.connectionLabel()));
        connection.setFlowRate(request.flowRate());
        connection.setConditionExpr(trimToNull(request.conditionExpr()));
        connection.setCapacity(requireNonNegativeCapacity(request.capacity()));
        connection.setFailurePolicy(normalizeFailurePolicy(request.failurePolicy()));
        connection.setUnit(trimToNull(request.unit()));
        connection.setDelayTimeSec(defaultIfNull(request.delayTimeSec(), BigDecimal.ZERO));
        requireDelay(connection.getDelayTimeSec());
        connection.setLossRate(defaultIfNull(request.lossRate(), BigDecimal.ZERO));
        requireLossRate(connection.getLossRate());
        connection.setPriority(request.priority() != null ? request.priority() : 0);
        connection.setDefaultRouteYn(Boolean.TRUE.equals(request.defaultRoute()) ? "Y" : "N");
        if (request.splitKind() != null) {
            applySplit(connection, request.splitKind(), request.splitValue());
        } else if (request.splitValue() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "splitValue needs splitKind.");
        }
        connection.setDeletedYn(NOT_DELETED);
        connection.setVersion(1);
        connection.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        validateConnectionContract(connection, null, true);
        ProcessConnectionResponse response = toResponse(processConnectionRepository.save(connection));
        graphSyncService.broadcast(Type.CONNECTION_CREATED, response.workflowId(), response.connectionId());
        return response;
    }

    /** A connection's delay is 0 or more seconds (docs/architecture/adr/ADR-010-connection-delay.md decision 8). */
    private static void requireDelay(BigDecimal delayTimeSec) {
        if (delayTimeSec.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "delayTimeSec is 0 or more seconds.");
        }
    }

    /** A connection loses a fraction of what it carries, 0 up to but not 1 (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 2). */
    private static void requireLossRate(BigDecimal lossRate) {
        if (lossRate.signum() < 0 || lossRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "lossRate is a fraction of at least 0 and below 1 (0.05 is 5%).");
        }
    }

    /**
     * A Split gateway's share on the connection (docs/architecture/adr/ADR-008-quantity-split.md decision 2): a percent
     * above 0 and at most 100 with up to 2 decimals, a fixed quantity above 0 with up to 4, or the rest without a value.
     */
    private static void applySplit(ProcessConnection connection, String kind, BigDecimal value) {
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "share" -> {
                if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0
                    || value.stripTrailingZeros().scale() > 2) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "A share split takes a percent above 0 and at most 100, with up to 2 decimals.");
                }
            }
            case "fixed" -> {
                if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 4
                    || value.precision() - value.scale() > 15) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "A fixed split takes a quantity above 0 with up to 4 decimals.");
                }
            }
            case "rest" -> {
                if (value != null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "A rest split takes no splitValue.");
                }
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "splitKind is share, fixed or rest.");
        }
        connection.setSplitKind(normalized);
        connection.setSplitValue(value);
    }

    @Override
    public ProcessConnectionResponse getConnection(String connectionId) {
        ProcessConnection connection = workflowAccess.requireConnectionReadAccess(connectionId);
        workflowAccess.requireWorkflowReadAccess(connection.getWorkflowId());
        return toResponse(connection);
    }

    @Override
    @Transactional
    public ProcessConnectionResponse updateConnection(String connectionId, ProcessConnectionUpdateRequest request) {
        ProcessConnection connection = workflowAccess.requireConnectionWriteAccess(connectionId);
        WorkflowText.requireStorable(request.fromIoId(), "fromIoId");
        WorkflowText.requireStorable(request.toIoId(), "toIoId");
        WorkflowText.requireStorable(request.itemId(), "itemId");
        WorkflowText.requireStorable(request.sourceHandle(), "sourceHandle");
        WorkflowText.requireStorable(request.targetHandle(), "targetHandle");
        WorkflowText.requireStorable(request.connectionType(), "connectionType");
        WorkflowText.requireStorable(request.conditionExpr(), "conditionExpr");
        lockWorkflowForConnection(connection);
        Process fromProcess = workflowAccess.requireProcessWriteAccess(connection.getFromProcessId());
        Process toProcess = workflowAccess.requireProcessWriteAccess(connection.getToProcessId());
        String previousFromIoId = connection.getFromIoId();
        String previousToIoId = connection.getToIoId();

        if (request.fromIoId() != null) {
            connection.setFromIoId(validateProcessIo(request.fromIoId(), fromProcess.getProcessId(), "output"));
        }
        if (request.toIoId() != null) {
            connection.setToIoId(validateProcessIo(request.toIoId(), toProcess.getProcessId(), "input"));
        }
        if (request.itemId() != null) {
            connection.setItemId(validateItem(request.itemId(), connection.getProjectId()));
        }
        if (request.sourceHandle() != null) {
            connection.setSourceHandle(resolveHandle(request.sourceHandle(), connection.getFromIoId(), "out"));
        } else if (connection.getSourceHandle() == null
            || (request.fromIoId() != null && !Objects.equals(previousFromIoId, connection.getFromIoId())
                && Objects.equals(connection.getSourceHandle(), resolveHandle(null, previousFromIoId, "out")))) {
            connection.setSourceHandle(resolveHandle(null, connection.getFromIoId(), "out"));
        }
        if (request.targetHandle() != null) {
            connection.setTargetHandle(resolveHandle(request.targetHandle(), connection.getToIoId(), "in"));
        } else if (connection.getTargetHandle() == null
            || (request.toIoId() != null && !Objects.equals(previousToIoId, connection.getToIoId())
                && Objects.equals(connection.getTargetHandle(), resolveHandle(null, previousToIoId, "in")))) {
            connection.setTargetHandle(resolveHandle(null, connection.getToIoId(), "in"));
        }
        if (hasText(request.connectionType())) {
            connection.setConnectionType(request.connectionType().trim().toLowerCase(Locale.ROOT));
        }
        if (request.connectionLabel() != null) {
            connection.setConnectionLabel(optionalText(request.connectionLabel(), "connectionLabel", 100));
        }
        if (request.flowRate() != null) {
            connection.setFlowRate(decimalOrNull(request.flowRate(), "flowRate", MAX_FLOW_RATE, 4, "numeric(14,4)"));
        }
        if (request.conditionExpr() != null) {
            connection.setConditionExpr(trimToNull(request.conditionExpr()));
        }
        if (Boolean.TRUE.equals(request.clearCapacity()) && request.capacity() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "capacity cannot be supplied with clearCapacity.");
        }
        if (Boolean.TRUE.equals(request.clearCapacity())) {
            connection.setCapacity(null);
        } else if (request.capacity() != null) {
            connection.setCapacity(requireNonNegativeCapacity(request.capacity()));
        }
        if (request.failurePolicy() != null) {
            connection.setFailurePolicy(normalizeFailurePolicy(request.failurePolicy()));
        }
        if (request.unit() != null) {
            connection.setUnit(optionalText(request.unit(), "unit", 20));
        }
        if (request.delayTimeSec() != null) {
            connection.setDelayTimeSec(defaultIfNull(decimalOrNull(request.delayTimeSec(), "delayTimeSec",
                MAX_DELAY_TIME_SEC, 2, "numeric(10,2)"), BigDecimal.ZERO));
            requireDelay(connection.getDelayTimeSec());
        }
        if (request.lossRate() != null) {
            connection.setLossRate(defaultIfNull(decimalOrNull(request.lossRate(), "lossRate",
                MAX_LOSS_RATE, 4, "numeric(5,4)"), BigDecimal.ZERO));
            requireLossRate(connection.getLossRate());
        }
        if (request.priority() != null) {
            connection.setPriority(integerOrZero(request.priority()));
        }
        if (request.defaultRoute() != null) {
            connection.setDefaultRouteYn(request.defaultRoute() ? "Y" : "N");
        }
        boolean clearSplit = Boolean.TRUE.equals(request.clearSplit());
        if (clearSplit && (request.splitKind() != null || request.splitValue() != null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "splitKind and splitValue cannot be supplied with clearSplit.");
        }
        if (clearSplit) {
            connection.setSplitKind(null);
            connection.setSplitValue(null);
        } else if (request.splitKind() != null) {
            applySplit(connection, request.splitKind(), request.splitValue());
        } else if (request.splitValue() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "splitValue needs splitKind.");
        }
        connection.setVersion(connection.getVersion() + 1);
        connection.setVersionNonce(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
        validateConnectionContract(connection, connectionId, true);
        ProcessConnectionResponse response = toResponse(processConnectionRepository.save(connection));
        graphSyncService.broadcast(Type.CONNECTION_UPDATED, response.workflowId(), response.connectionId());
        return response;
    }

    @Override
    @Transactional
    public void deleteConnection(String connectionId) {
        ProcessConnection connection = workflowAccess.requireConnectionWriteAccess(connectionId);
        lockWorkflowForConnection(connection);
        String workflowId = connection.getWorkflowId();
        connection.setDeletedYn(DELETED);
        processConnectionRepository.save(connection);
        graphSyncService.broadcast(Type.CONNECTION_DELETED, workflowId, connectionId);
    }

    private void lockWorkflowForConnection(ProcessConnection connection) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(connection.getWorkflowId());
        lockWorkflow(workflow);
        entityManager.refresh(connection);
        if (!NOT_DELETED.equals(connection.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void lockWorkflow(Workflow workflow) {
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(workflow);
        if (!NOT_DELETED.equals(workflow.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private String validateProcessIo(String processIoId, String processId, String direction) {
        if (!hasText(processIoId)) {
            return null;
        }
        ProcessIo processIo = processIoRepository.findByProcessIoIdAndDeletedYn(processIoId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!processId.equals(processIo.getProcessId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!direction.equalsIgnoreCase(processIo.getDirection())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Connection source must be an output port and target must be an input port.");
        }
        return processIo.getProcessIoId();
    }

    private static String optionalText(JsonNode value, String field, int maxLength) {
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be a string or null.");
        }
        WorkflowText.requireStorable(value.textValue(), field);
        if (value.textValue().length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " must be at most " + maxLength + " characters.");
        }
        return trimToNull(value.textValue());
    }

    private static BigDecimal decimalOrNull(JsonNode value, String field, BigDecimal max,
        int fractionDigits, String columnType) {
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be a number or null.");
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.abs().compareTo(max) > 0 || decimal.stripTrailingZeros().scale() > fractionDigits) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must fit " + columnType + ".");
        }
        return decimal;
    }

    private static int integerOrZero(JsonNode value) {
        if (value.isNull()) {
            return 0;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "priority must be an integer.");
        }
        return value.intValue();
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    void validateConnectionContract(ProcessConnection connection, String excludedConnectionId) {
        validateConnectionContract(connection, excludedConnectionId, false);
    }

    private void validateConnectionContract(ProcessConnection connection, String excludedConnectionId, boolean inferItem) {
        ConditionExpression condition = null;
        if (hasText(connection.getConditionExpr())) {
            condition = ConditionExpression.compile(connection.getConditionExpr());
        }
        if (connection.getFromProcessId().equals(connection.getToProcessId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A connection cannot link a process to itself.");
        }
        if (connection.getFromIoId() != null && connection.getToIoId() != null &&
            processConnectionRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(
                connection.getWorkflowId(), NOT_DELETED).stream().anyMatch(existing ->
                !existing.getConnectionId().equals(excludedConnectionId)
                    && connection.getFromIoId().equals(existing.getFromIoId())
                    && connection.getToIoId().equals(existing.getToIoId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Ports are already connected.");
        }
        String fromIoId = connection.getFromIoId();
        String toIoId = connection.getToIoId();
        ProcessIo source = fromIoId == null ? null : processIoRepository.findByProcessIoIdAndDeletedYn(fromIoId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Connection fromIoId points to a deleted port."));
        ProcessIo target = toIoId == null ? null : processIoRepository.findByProcessIoIdAndDeletedYn(toIoId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Connection toIoId points to a deleted port."));
        if (source != null && (!source.getProcessId().equals(connection.getFromProcessId())
            || !"output".equalsIgnoreCase(source.getDirection()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection fromIoId must be an output port of fromProcessId.");
        }
        if (target != null && (!target.getProcessId().equals(connection.getToProcessId())
            || !"input".equalsIgnoreCase(target.getDirection()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection toIoId must be an input port of toProcessId.");
        }
        PortSchema sourceSchema = source == null ? null : PortSchema.parseStored(source.getSchemaJson());
        if (condition != null) {
            condition.requireDeclaredAttributes(sourceSchema == null ? null : sourceSchema.properties().keySet());
        }
        String sourceItem = source == null ? null : source.getItemId();
        String targetItem = target == null ? null : target.getItemId();
        String selectedItem = connection.getItemId();
        if (sourceItem != null && targetItem != null && !sourceItem.equals(targetItem)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connected ports itemId must match.");
        }
        if (selectedItem != null && ((sourceItem != null && !selectedItem.equals(sourceItem))
            || (targetItem != null && !selectedItem.equals(targetItem)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection itemId must match the ports.");
        }
        if (inferItem && selectedItem == null && sourceItem != null && sourceItem.equals(targetItem)) {
            connection.setItemId(sourceItem);
        }
        if (source != null && target != null) {
            String sourceType = defaultIfBlank(source.getResourceType(), source.getIoType());
            String targetType = defaultIfBlank(target.getResourceType(), target.getIoType());
            if (!sourceType.equals(targetType)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Connected ports resourceType must match.");
            }
            PortSchema targetSchema = PortSchema.parseStored(target.getSchemaJson());
            PortSchema.requireCompatible(sourceSchema, targetSchema);
        }
        Set<String> types = new java.util.HashSet<>();
        for (String unit : new String[] {source == null ? null : source.getUnit(),
            target == null ? null : target.getUnit(), connection.getUnit()}) {
            if (unit != null && !unit.isBlank()) {
                catalogQuery.findUnitType(unit).ifPresent(types::add);
            }
        }
        if (types.size() > 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection unit type must match port units.");
        }
    }

    private String validateItem(String itemId, String projectId) {
        if (!hasText(itemId)) {
            return null;
        }
        CatalogItemView item = catalogQuery.findActiveItem(itemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!projectId.equals(item.projectId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        return item.itemId();
    }

    private static void validateProcessMembership(Workflow workflow, Process fromProcess, Process toProcess) {
        if (!workflow.getWorkflowId().equals(fromProcess.getWorkflowId())
            || !workflow.getWorkflowId().equals(toProcess.getWorkflowId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
    }

    private static ProcessConnectionResponse toResponse(ProcessConnection connection) {
        return new ProcessConnectionResponse(
            connection.getConnectionId(),
            connection.getProjectId(),
            connection.getWorkflowId(),
            connection.getFromProcessId(),
            connection.getToProcessId(),
            connection.getFromIoId(),
            connection.getToIoId(),
            connection.getItemId(),
            connection.getSourceHandle(),
            connection.getTargetHandle(),
            connection.getConnectionType(),
            connection.getConnectionLabel(),
            connection.getFlowRate(),
            connection.getUnit(),
            connection.getDelayTimeSec(),
            connection.getLossRate(),
            connection.getPriority(),
            connection.getConditionExpr(),
            connection.getCapacity(),
            connection.getFailurePolicy(),
            connection.isDefaultRoute(),
            connection.getSplitKind(),
            connection.getSplitValue(),
            connection.getVersion(),
            connection.getVersionNonce()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : defaultValue;
    }

    private static BigDecimal defaultIfNull(BigDecimal value, BigDecimal defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static BigDecimal requireNonNegativeCapacity(BigDecimal capacity) {
        if (capacity != null && capacity.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection capacity cannot be negative.");
        }
        if (capacity != null && (capacity.compareTo(MAX_CAPACITY) > 0
            || capacity.stripTrailingZeros().scale() > 4)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection capacity must fit numeric(19,4).");
        }
        return capacity != null && capacity.signum() == 0 ? BigDecimal.ZERO : capacity;
    }

    private static String normalizeFailurePolicy(String value) {
        WorkflowText.requireStorable(value, "failurePolicy");
        String normalized = defaultIfBlank(value, "stop");
        if (!FAILURE_POLICIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown connection failure policy.");
        }
        return normalized;
    }

    private static String resolveHandle(String requestedHandle, String ioId, String defaultPrefix) {
        if (hasText(requestedHandle)) {
            return requestedHandle.trim();
        }
        if (hasText(ioId)) {
            return ioId.trim();
        }
        return defaultPrefix + "-default";
    }
}
