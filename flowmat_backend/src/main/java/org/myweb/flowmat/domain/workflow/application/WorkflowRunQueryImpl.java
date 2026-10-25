package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessIoRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.ProcessRunView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunQuery;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowRunView;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowRunQueryImpl implements WorkflowRunQuery {

    private static final String NOT_DELETED = "N";
    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {
    };

    private final WorkflowRepository workflowRepository;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<WorkflowRunView> findWorkflow(String workflowId) {
        return workflowRepository.findByWorkflowIdAndDeletedYn(workflowId, NOT_DELETED).map(row -> view(copy(row, Workflow.class)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowRunView lockWorkflow(String workflowId) {
        Workflow row = workflowRepository.findByWorkflowIdAndDeletedYn(workflowId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        if (!NOT_DELETED.equals(row.getDeletedYn())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return view(copy(row, Workflow.class));
    }

    @Override
    public Optional<ProcessRunView> findProcess(String processId) {
        return processRepository.findByProcessIdAndDeletedYn(processId, NOT_DELETED).map(row -> view(copy(row, Process.class)));
    }

    @Override
    public Optional<ProcessIoRunView> findProcessIo(String processIoId) {
        return processIoRepository.findByProcessIoIdAndDeletedYn(processIoId, NOT_DELETED).map(row -> view(copy(row, ProcessIo.class)));
    }

    @Override
    public Optional<ProcessRunView> revisionProcess(String snapshotJson, String processId) {
        return snapshotEntry(snapshotJson, "processes", "processId", processId).map(this::snapshotProcess);
    }

    @Override
    public Optional<ProcessIoRunView> revisionProcessIo(String snapshotJson, String processIoId) {
        return snapshotEntry(snapshotJson, "processIos", "processIoId", processIoId).map(this::snapshotProcessIo);
    }

    private Optional<JsonNode> snapshotEntry(String snapshotJson, String entries, String idField, String id) {
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(snapshotJson);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored workflow revision snapshot is invalid.", error);
        }
        JsonNode list = snapshot.path(entries);
        if (id == null || !list.isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : list) {
            if (id.equals(entry.path(idField).asText())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    @Override
    public ProcessRunView snapshotProcess(JsonNode entry) {
        return view(fromSnapshot(entry, Process.class));
    }

    @Override
    public ProcessIoRunView snapshotProcessIo(JsonNode entry) {
        // Snapshots carry schemaJson as a JSON object, while the entity keeps it as JSON text.
        JsonNode schema = entry.get("schemaJson");
        if (entry instanceof ObjectNode source && schema != null && !schema.isNull() && !schema.isTextual()) {
            ObjectNode copy = source.deepCopy();
            copy.put("schemaJson", schema.toString());
            return view(fromSnapshot(copy, ProcessIo.class));
        }
        return view(fromSnapshot(entry, ProcessIo.class));
    }

    private WorkflowRunView view(Workflow workflow) {
        return new WorkflowRunView(workflow.getWorkflowId(), workflow.getProjectId(), ruleFacts(workflow));
    }

    private ProcessRunView view(Process process) {
        return new ProcessRunView(process.getProcessId(), process.getProjectId(), process.getWorkflowId(), process.getProcessName(),
            ruleFacts(process));
    }

    private ProcessIoRunView view(ProcessIo io) {
        return new ProcessIoRunView(io.getProcessIoId(), io.getProcessId(), io.getItemId(), io.getDirection(), ruleFacts(io));
    }

    // Production used to read the row as JSON back into a detached entity; the view and its rule facts come from that copy.
    private <T> T copy(T row, Class<T> type) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(Hibernate.unproxy(row)), type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the workflow's rule facts.", error);
        }
    }

    private <T> T fromSnapshot(JsonNode entry, Class<T> type) {
        try {
            return objectMapper.treeToValue(entry, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored workflow revision process is invalid.", error);
        }
    }

    // The map the rule evaluator makes of an object (FlowRuleExpressionEvaluator.resolvePath), so each value keeps its form.
    private Map<String, Object> ruleFacts(Object copy) {
        return Collections.unmodifiableMap(objectMapper.convertValue(copy, PROPERTIES));
    }
}
