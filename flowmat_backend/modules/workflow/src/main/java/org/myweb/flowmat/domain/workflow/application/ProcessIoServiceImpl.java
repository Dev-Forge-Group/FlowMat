package org.myweb.flowmat.domain.workflow.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessIoUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessIoResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.contract.WorkflowText;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessIoServiceImpl implements ProcessIoService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";
    private static final String INPUT_DEFAULT_COLOR = "sky";
    private static final String OUTPUT_DEFAULT_COLOR = "emerald";
    private static final String DEFAULT_COLOR = "slate";
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999999.9999");

    private final ProcessIoRepository processIoRepository;
    private final ProcessConnectionRepository processConnectionRepository;
    private final ProcessConnectionServiceImpl connectionService;
    private final ProcessRepository processRepository;
    private final GraphSyncService graphSyncService;
    private final CatalogQuery catalogQuery;
    private final IdGenerator idGenerator;
    private final WorkflowAccessService workflowAccess;
    private final EntityManager entityManager;

    @Override
    public List<ProcessIoResponse> listProcessIos(String processId) {
        requireActiveWorkflowForRead(processId);
        return processIoRepository.findAllByProcessIdAndDeletedYnOrderByCreatedAtAsc(processId, NOT_DELETED).stream()
            .map(ProcessIoResponse::from)
            .toList();
    }

    @Override
    @Transactional
    public ProcessIoResponse createProcessIo(ProcessIoCreateRequest request) {
        WorkflowText.requireStorable(request.processId(), "processId");
        Process process = workflowAccess.requireProcessWriteAccess(request.processId());
        WorkflowText.requireStorable(request.itemId(), "itemId");
        WorkflowText.requireStorable(request.ioName(), "ioName");
        WorkflowText.requireStorable(request.ioType(), "ioType");
        WorkflowText.requireStorable(request.role(), "role");
        WorkflowText.requireStorable(request.resourceType(), "resourceType");
        WorkflowText.requireStorable(request.unit(), "unit");
        WorkflowText.requireStorable(request.formula(), "formula");
        WorkflowText.requireStorable(request.colorScheme(), "colorScheme");
        WorkflowText.requireStorable(request.validationRule(), "validationRule");
        lockWorkflowForProcess(process);

        ProcessIo processIo = new ProcessIo();
        processIo.setProcessIoId(idGenerator.generate());
        processIo.setProcessId(process.getProcessId());
        // The item is an optional binding (ADR-003): data, file and API ports have none.
        processIo.setItemId(hasText(request.itemId()) ? projectItemId(process, request.itemId()) : null);
        processIo.setIoName(trimToNull(request.ioName()));
        processIo.setDirection(normalizeDirection(request.direction()));
        processIo.setIoType(defaultIfBlank(request.ioType(), "material"));
        processIo.setRole(trimToNull(request.role()));
        processIo.setResourceType(defaultIfBlank(request.resourceType(), processIo.getIoType()));
        // Left out stays empty; only ports that need them must give them (docs/domain/port-measurement.md PM4).
        processIo.setQuantity(request.quantity() == null ? null : normalizeQuantity(request.quantity()));
        processIo.setUnit(trimToNull(request.unit()));
        processIo.setFormula(trimToNull(request.formula()));
        processIo.setSchemaJson(writeSchema(request.schemaJson()));
        processIo.setValidationRule(trimToNull(request.validationRule()));
        processIo.setColorScheme(defaultColorScheme(request.colorScheme(), processIo.getDirection()));
        processIo.setRequiredYn(normalizeYn(request.requiredYn(), "Y", "requiredYn"));
        processIo.setAllowShortageYn(normalizeYn(request.allowShortageYn(), "N", "allowShortageYn"));
        processIo.setDeletedYn(NOT_DELETED);
        validatePortContract(processIo);
        ProcessIoResponse response = ProcessIoResponse.from(processIoRepository.save(processIo));
        graphSyncService.broadcast(Type.PORT_CREATED, process.getWorkflowId(), response.processIoId());
        return response;
    }

    @Override
    public ProcessIoResponse getProcessIo(String processIoId) {
        ProcessIo processIo = workflowAccess.requireProcessIoReadAccess(processIoId);
        requireActiveWorkflowForRead(processIo.getProcessId());
        return ProcessIoResponse.from(processIo);
    }

    @Override
    @Transactional
    public ProcessIoResponse updateProcessIo(String processIoId, ProcessIoUpdateRequest request) {
        ProcessIo processIo = workflowAccess.requireProcessIoWriteAccess(processIoId);
        WorkflowText.requireStorable(request.itemId(), "itemId");
        WorkflowText.requireStorable(request.ioName(), "ioName");
        WorkflowText.requireStorable(request.ioType(), "ioType");
        WorkflowText.requireStorable(request.role(), "role");
        WorkflowText.requireStorable(request.resourceType(), "resourceType");
        WorkflowText.requireStorable(request.unit(), "unit");
        WorkflowText.requireStorable(request.formula(), "formula");
        WorkflowText.requireStorable(request.colorScheme(), "colorScheme");
        WorkflowText.requireStorable(request.validationRule(), "validationRule");
        lockWorkflowForPort(processIo);
        List<String> contractBefore = contractFields(processIo);

        if (Boolean.TRUE.equals(request.clearItem()) && hasText(request.itemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "itemId cannot be supplied with clearItem.");
        }
        if (Boolean.TRUE.equals(request.clearItem())) {
            processIo.setItemId(null);
        } else if (hasText(request.itemId())) {
            Process process = workflowAccess.requireProcessWriteAccess(processIo.getProcessId());
            processIo.setItemId(projectItemId(process, request.itemId()));
        }
        if (request.ioName() != null) {
            processIo.setIoName(trimToNull(request.ioName()));
        }
        if (request.direction() != null) {
            processIo.setDirection(normalizeDirection(request.direction()));
        }
        if (hasText(request.ioType())) {
            processIo.setIoType(request.ioType().trim().toLowerCase(Locale.ROOT));
        }
        if (request.role() != null) {
            processIo.setRole(trimToNull(request.role()));
        }
        if (hasText(request.resourceType())) {
            processIo.setResourceType(request.resourceType().trim().toLowerCase(Locale.ROOT));
        }
        // clearMeasure empties both first; a quantity or unit sent with it is set again (PM5).
        if (Boolean.TRUE.equals(request.clearMeasure())) {
            processIo.setQuantity(null);
            processIo.setUnit(null);
        }
        if (request.quantity() != null) {
            processIo.setQuantity(normalizeQuantity(request.quantity()));
        }
        if (hasText(request.unit())) {
            processIo.setUnit(request.unit().trim());
        }
        if (request.formula() != null) {
            processIo.setFormula(trimToNull(request.formula()));
        }
        if (Boolean.TRUE.equals(request.clearSchema()) && request.schemaJson() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "schemaJson cannot be supplied with clearSchema.");
        }
        if (Boolean.TRUE.equals(request.clearSchema())) {
            processIo.setSchemaJson(null);
        } else if (request.schemaJson() != null) {
            processIo.setSchemaJson(writeSchema(request.schemaJson()));
        }
        if (request.validationRule() != null) {
            processIo.setValidationRule(trimToNull(request.validationRule()));
        }
        if (request.colorScheme() != null) {
            processIo.setColorScheme(normalizeColorScheme(request.colorScheme()));
        } else if (request.direction() != null && processIo.getColorScheme() == null) {
            processIo.setColorScheme(defaultColorScheme(null, processIo.getDirection()));
        }
        if (request.requiredYn() != null) {
            processIo.setRequiredYn(normalizeYn(request.requiredYn(), processIo.getRequiredYn(), "requiredYn"));
        }
        if (request.allowShortageYn() != null) {
            processIo.setAllowShortageYn(normalizeYn(request.allowShortageYn(), processIo.getAllowShortageYn(), "allowShortageYn"));
        }
        validatePortContract(processIo);
        if (!Objects.equals(contractBefore, contractFields(processIo))) {
            List<String> brokenConnections = processConnectionRepository.findLiveConnectionsForPort(processIoId).stream()
                .filter(connection -> breaksConnection(connection))
                .map(ProcessConnection::getConnectionId)
                .toList();
            if (!brokenConnections.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "Port change would break connection(s): " + String.join(", ", brokenConnections));
            }
        }
        ProcessIo saved = processIoRepository.save(processIo);
        Process parentProcess = workflowAccess.requireProcessWriteAccess(saved.getProcessId());
        graphSyncService.broadcast(Type.PORT_UPDATED, parentProcess.getWorkflowId(), saved.getProcessIoId());
        return ProcessIoResponse.from(saved);
    }

    @Override
    @Transactional
    public void deleteProcessIo(String processIoId) {
        ProcessIo processIo = workflowAccess.requireProcessIoWriteAccess(processIoId);
        lockWorkflowForPort(processIo);
        Process parentProcess = workflowAccess.requireProcessWriteAccess(processIo.getProcessId());
        String workflowId = parentProcess.getWorkflowId();
        for (ProcessConnection connection : processConnectionRepository.findLiveConnectionsForPort(processIoId)) {
            connection.setDeletedYn(DELETED);
            processConnectionRepository.save(connection);
            graphSyncService.broadcast(Type.CONNECTION_DELETED, connection.getWorkflowId(), connection.getConnectionId());
        }
        processIo.setDeletedYn(DELETED);
        processIoRepository.save(processIo);
        graphSyncService.broadcast(Type.PORT_DELETED, workflowId, processIoId);
    }

    /** The id of an active item of the process's project: 404 when there is no such item, 400 when it is another project's. */
    private String projectItemId(Process process, String itemId) {
        CatalogItemView item = catalogQuery.findActiveItem(itemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        validateSameProject(process.getProjectId(), item.projectId());
        return item.itemId();
    }

    private static String writeSchema(com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema == null) {
            return null;
        }
        if (!schema.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Port schema must be a JSON object.");
        }
        if (schema.toString().length() > 65536) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Port schema is too large.");
        }
        PortSchema.parse(schema);
        return schema.toString();
    }

    private void requireActiveWorkflowForRead(String processId) {
        Process process = workflowAccess.requireProcessReadAccess(processId);
        workflowAccess.requireWorkflowReadAccess(process.getWorkflowId());
    }

    private void lockWorkflowForPort(ProcessIo port) {
        Process process = workflowAccess.requireProcessWriteAccess(port.getProcessId());
        lockWorkflowForProcess(process);
        entityManager.refresh(port);
        if (!NOT_DELETED.equals(port.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private void lockWorkflowForProcess(Process process) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(process.getWorkflowId());
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(workflow);
        if (!NOT_DELETED.equals(workflow.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        entityManager.refresh(process);
        if (!NOT_DELETED.equals(process.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static void validatePortContract(ProcessIo port) {
        validatePortValues(port);
        PortSchema schema = PortSchema.parseStored(port.getSchemaJson());
        if (hasText(port.getValidationRule())) {
            ConditionExpression rule = ConditionExpression.compile(port.getValidationRule());
            rule.requireDeclaredAttributes(schema == null ? null : schema.properties().keySet());
        }
    }

    static void validatePortValues(ProcessIo port) {
        normalizeDirection(port.getDirection());
        normalizeYn(port.getRequiredYn(), "Y", "requiredYn");
        normalizeYn(port.getAllowShortageYn(), "N", "allowShortageYn");
        if (port.getQuantity() != null && port.getQuantity().signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must be zero or greater.");
        }
        if (port.getQuantity() != null && (port.getQuantity().compareTo(MAX_QUANTITY) > 0
            || port.getQuantity().stripTrailingZeros().scale() > 4)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must fit numeric(14,4).");
        }
        // Manufacturing ports need both; others may leave them out, but a quantity needs its unit (PM2-PM3).
        if (needsMeasure(port) && (port.getQuantity() == null || !hasText(port.getUnit()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Material and product ports need a quantity and unit.");
        }
        if (port.getQuantity() != null && !hasText(port.getUnit())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A port quantity needs a unit.");
        }
    }

    /** Material and product ports, and any port bound to an item (docs/domain/port-measurement.md PM2). */
    private static boolean needsMeasure(ProcessIo port) {
        String type = defaultIfBlank(port.getResourceType(), defaultIfBlank(port.getIoType(), "material"));
        return "material".equals(type) || "product".equals(type) || hasText(port.getItemId());
    }

    private boolean breaksConnection(ProcessConnection connection) {
        try {
            connectionService.validateConnectionContract(connection, connection.getConnectionId());
            return false;
        } catch (BusinessException exception) {
            return true;
        }
    }

    private static List<String> contractFields(ProcessIo port) {
        return Arrays.asList(port.getDirection(), port.getResourceType(), port.getItemId(),
            port.getUnit(), port.getSchemaJson());
    }

    private static void validateSameProject(String processProjectId, String itemProjectId) {
        if (!processProjectId.equals(itemProjectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
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

    private static BigDecimal normalizeQuantity(BigDecimal quantity) {
        return quantity.signum() == 0 ? BigDecimal.ZERO : quantity;
    }

    private static String normalizeDirection(String value) {
        WorkflowText.requireStorable(value, "direction");
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("input", "output").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "direction must be input or output.");
        }
        return normalized;
    }

    private static String normalizeYn(String value, String defaultValue, String field) {
        WorkflowText.requireStorable(value, field);
        String normalized = value == null ? defaultValue : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("Y", "N").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be Y or N.");
        }
        return normalized;
    }

    private static String defaultColorScheme(String value, String direction) {
        if (hasText(value)) {
            return normalizeColorScheme(value);
        }
        if ("input".equalsIgnoreCase(direction)) {
            return INPUT_DEFAULT_COLOR;
        }
        if ("output".equalsIgnoreCase(direction)) {
            return OUTPUT_DEFAULT_COLOR;
        }
        return DEFAULT_COLOR;
    }

    private static String normalizeColorScheme(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
