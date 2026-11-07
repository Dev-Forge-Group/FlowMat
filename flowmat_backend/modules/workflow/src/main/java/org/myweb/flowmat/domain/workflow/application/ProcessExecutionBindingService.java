package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionBindingRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.NodeExecutionBindingResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessExecutionBindingResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessExecutionBinding;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessExecutionBindingRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessExecutionBindingService {
    private static final Set<String> GATEWAYS = Set.of("exclusive", "join", "split");
    private final WorkflowAccessService access;
    private final ProcessExecutionBindingRepository bindings;
    private final ProcessIoRepository ports;
    private final ConnectorProfileQuery profiles;
    private final ConnectorMappingQuery mappings;
    private final EntityManager entities;
    private final ObjectMapper json;

    public ProcessExecutionBindingResponse get(String processId) {
        Process process = access.requireProcessReadAccess(processId);
        access.requireWorkflowReadAccess(process.getWorkflowId());
        return bindings.findById(processId).map(this::response).orElseGet(() ->
            new ProcessExecutionBindingResponse(processId, null, null, null, null, null, 0));
    }

    /** The controller invokes this before reading an untrusted, potentially slow request body. */
    public void authorizeWrite(String processId) {
        Process process = access.requireProcessWriteAccess(processId);
        access.requireWorkflowWriteAccess(process.getWorkflowId());
    }

    @Transactional
    public ProcessExecutionBindingResponse set(String processId, ProcessExecutionBindingRequest request) {
        Process process = access.requireProcessWriteAccess(processId);
        Workflow workflow = access.requireWorkflowWriteAccess(process.getWorkflowId());
        // Publication uses this same lock. A binding is never partially copied into a new revision.
        entities.refresh(workflow, LockModeType.PESSIMISTIC_WRITE);
        if (!"N".equals(workflow.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        entities.refresh(process, LockModeType.PESSIMISTIC_WRITE);
        if (!"N".equals(process.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        access.requireWorkflowWriteAccess(process.getWorkflowId());
        boolean clear = validateRequest(request);
        if (!clear) requireOrdinary(process);
        ProcessExecutionBinding row = bindings.findById(processId).orElseGet(() -> {
            ProcessExecutionBinding fresh = new ProcessExecutionBinding();
            fresh.setProcessId(processId);
            return fresh;
        });
        long version = row.getBindingVersion();
        boolean replay = version > 0 && request.expectedVersion() == version - 1 && same(row, request);
        if (request.expectedVersion() != version && !replay) {
            throw conflict("expectedVersion changed; reload the execution binding before saving a different one.");
        }
        if (!replay && version == Long.MAX_VALUE) throw conflict("expectedVersion has reached its limit.");
        if (!clear) {
            ProcessIo port = ports.findByProcessIoIdAndDeletedYn(request.outputPortId(), "N")
                .orElseThrow(() -> invalid("outputPortId must identify an active output port of this process."));
            requirePort(process, port, ErrorCode.BAD_REQUEST);
            profiles.requireOperation(process.getProjectId(), request.profileVersionId(), request.operationId());
            mappings.validate(request.requestMapping());
            mappings.validate(request.responseMapping());
            requireStorableMapping(request.requestMapping(), "requestMapping");
            requireStorableMapping(request.responseMapping(), "responseMapping");
        }
        if (replay) return response(row);
        row.setProfileVersionId(request.profileVersionId());
        row.setOperationId(request.operationId());
        row.setOutputPortId(request.outputPortId());
        row.setRequestMapping(clear ? null : write(request.requestMapping()));
        row.setResponseMapping(clear ? null : write(request.responseMapping()));
        row.setBindingVersion(version + 1);
        return response(bindings.save(row));
    }

    /** Called by publication after its workflow lock, using the same active rows that enter the snapshot. */
    public List<NodeExecutionBindingResponse> snapshotBindings(String projectId, List<Process> processes,
        List<ProcessIo> activePorts) {
        if (processes.isEmpty()) return List.of();
        Map<String, ProcessExecutionBinding> byProcess = new HashMap<>();
        for (ProcessExecutionBinding row : bindings.findAllByProcessIdIn(processes.stream().map(Process::getProcessId).toList())) {
            if (row.getProfileVersionId() != null) byProcess.put(row.getProcessId(), row);
        }
        Map<String, ProcessIo> byPort = new HashMap<>();
        for (ProcessIo port : activePorts) byPort.put(port.getProcessIoId(), port);
        return processes.stream().filter(process -> byProcess.containsKey(process.getProcessId())).map(process -> {
            requireOrdinary(process);
            ProcessExecutionBinding row = byProcess.get(process.getProcessId());
            requirePort(process, byPort.get(row.getOutputPortId()), ErrorCode.CONFLICT);
            profiles.requireOperation(projectId, row.getProfileVersionId(), row.getOperationId());
            JsonNode requestMapping = read(row.getRequestMapping());
            JsonNode responseMapping = read(row.getResponseMapping());
            try {
                mappings.validate(requestMapping);
                mappings.validate(responseMapping);
                requireStorableMapping(requestMapping, "requestMapping");
                requireStorableMapping(responseMapping, "responseMapping");
            } catch (BusinessException exception) {
                throw conflict("Process " + process.getProcessId() + " has an invalid execution binding mapping.");
            }
            return new NodeExecutionBindingResponse(process.getProcessId(), row.getProfileVersionId(), row.getOperationId(),
                row.getOutputPortId(), requestMapping, responseMapping);
        }).toList();
    }

    private boolean validateRequest(ProcessExecutionBindingRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw invalid("expectedVersion is required, a nonnegative integer.");
        }
        boolean clear = request.profileVersionId() == null && request.operationId() == null && request.outputPortId() == null
            && request.requestMapping() == null && request.responseMapping() == null;
        if (clear) return true;
        requireId(request.profileVersionId(), 50, "profileVersionId");
        requireId(request.operationId(), 100, "operationId");
        requireId(request.outputPortId(), 50, "outputPortId");
        if (request.requestMapping() == null || !request.requestMapping().isObject()) throw invalid("requestMapping must be an object.");
        if (request.responseMapping() == null || !request.responseMapping().isObject()) throw invalid("responseMapping must be an object.");
        return false;
    }

    private static void requireId(String value, int max, String field) {
        StorableText.require(value, field);
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim())) {
            throw invalid(field + " must be nonblank text of at most " + max + " characters, without surrounding whitespace.");
        }
    }

    private static void requireStorableMapping(JsonNode mapping, String field) {
        // JSON allows escaped NUL and isolated surrogate code units; PostgreSQL jsonb does not.
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(mapping);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isTextual()) StorableText.require(node.textValue(), field);
            else if (node.isObject()) node.fields().forEachRemaining(entry -> {
                StorableText.require(entry.getKey(), field);
                pending.add(entry.getValue());
            });
            else if (node.isArray()) node.forEach(pending::add);
        }
    }

    private static void requireOrdinary(Process process) {
        if (process.getNodeType() != null && GATEWAYS.contains(process.getNodeType().toLowerCase(java.util.Locale.ROOT))) {
            throw conflict("Gateway processes cannot have an execution binding.");
        }
    }

    private static void requirePort(Process process, ProcessIo port, ErrorCode code) {
        if (port == null || !process.getProcessId().equals(port.getProcessId()) || !"output".equals(port.getDirection())
            || !"N".equals(port.getDeletedYn())) {
            throw new BusinessException(code, "outputPortId must identify an active output port of this process.");
        }
    }

    private boolean same(ProcessExecutionBinding row, ProcessExecutionBindingRequest request) {
        return Objects.equals(row.getProfileVersionId(), request.profileVersionId())
            && Objects.equals(row.getOperationId(), request.operationId()) && Objects.equals(row.getOutputPortId(), request.outputPortId())
            && Objects.equals(read(row.getRequestMapping()), request.requestMapping())
            && Objects.equals(read(row.getResponseMapping()), request.responseMapping());
    }

    private ProcessExecutionBindingResponse response(ProcessExecutionBinding row) {
        return new ProcessExecutionBindingResponse(row.getProcessId(), row.getProfileVersionId(), row.getOperationId(),
            row.getOutputPortId(), read(row.getRequestMapping()), read(row.getResponseMapping()), row.getBindingVersion());
    }

    private JsonNode read(String value) {
        if (value == null) return null;
        try {
            return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored execution binding mapping is invalid.", exception);
        }
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Execution binding mapping could not be saved.", exception); }
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
}
