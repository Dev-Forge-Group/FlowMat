package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.annotation.application.CanvasAnnotationService;
import org.myweb.flowmat.domain.workflow.api.dto.response.NodeExecutionPolicyResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.NodeExecutionBindingResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionSnapshot;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionSummaryResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowValidationResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowRevision;
import org.myweb.flowmat.domain.workflow.editor.application.WorkflowEditorDocumentService;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRevisionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowRevisionService {

    /**
     * 2: connections' loss rates apply in runs (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 8); 3: and
     * their delays (docs/architecture/adr/ADR-010-connection-delay.md decision 7).
     */
    private static final int SCHEMA_VERSION = 3;
    private static final String NOT_DELETED = "N";

    private final WorkflowRevisionRepository repository;
    private final ProcessRepository processRepository;
    private final ProcessIoRepository processIoRepository;
    private final ProcessConnectionRepository connectionRepository;
    private final CanvasAnnotationService annotationService;
    private final WorkflowEditorDocumentService editorDocumentService;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccessService workflowAccess;
    private final WorkflowValidationService validationService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final ProcessExecutionBindingService bindingService;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WorkflowRevisionResponse publish(String workflowId) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        // Serialize publishers of the same draft; the draft remains editable after publication.
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        WorkflowValidationResponse validation = validationService.validate(workflowId);
        if (validation.errors() > 0) {
            String summary = validation.issues().stream()
                .filter(issue -> "error".equals(issue.severity()))
                .limit(3)
                .map(issue -> issue.code() + ": " + issue.message())
                .collect(java.util.stream.Collectors.joining("; "));
            throw new BusinessException(ErrorCode.CONFLICT,
                "Workflow has " + validation.errors() + " error(s): " + summary);
        }
        int nextNumber = repository.findTopByWorkflowIdOrderByRevisionNoDesc(workflowId)
            .map(previous -> previous.getRevisionNo() + 1)
            .orElse(1);

        List<Process> processes = processRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(
            workflowId, NOT_DELETED);
        List<String> processIds = processes.stream().map(Process::getProcessId).toList();
        List<ProcessIo> processIos = processIds.isEmpty() ? List.of()
            : processIoRepository.findAllByProcessIdInAndDeletedYnOrderByCreatedAtAsc(processIds, NOT_DELETED);
        List<ProcessConnection> connections = connectionRepository
            .findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, NOT_DELETED);
        List<NodeExecutionBindingResponse> bindings = bindingService.snapshotBindings(workflow.getProjectId(), processes, processIos);
        int schemaVersion = bindings.isEmpty() ? SCHEMA_VERSION : 4;
        WorkflowRevisionSnapshot snapshot = new WorkflowRevisionSnapshot(
            schemaVersion,
            WorkflowCanvasServiceImpl.toWorkflowResponse(workflow),
            readJson(workflow.getCanvasSnapshot()),
            readJson(workflow.getSimulationConfig()),
            processes.stream().map(WorkflowCanvasServiceImpl::toProcessResponse).toList(),
            processIos.stream().map(WorkflowCanvasServiceImpl::toProcessIoResponse).toList(),
            connections.stream().map(WorkflowCanvasServiceImpl::toConnectionResponse).toList(),
            annotationService.list(workflowId),
            editorDocumentService.getDocument(workflowId),
            processes.stream().filter(Process::hasExecutionPolicy)
                .map(process -> new NodeExecutionPolicyResponse(process.getProcessId(), process.getTimeoutSeconds(),
                    process.getRetryLimit(), process.getRetryDelaySeconds(), process.getRetryBackoff(),
                    process.getMaxRetryDelaySeconds(), process.getConcurrencyLimit()))
                .toList(),
            bindings
        );

        WorkflowRevision revision = new WorkflowRevision();
        revision.setWorkflowRevisionId(idGenerator.generate());
        revision.setWorkflowId(workflowId);
        revision.setRevisionNo(nextNumber);
        revision.setStatus("published");
        revision.setSchemaVersion(schemaVersion);
        revision.setSnapshotJson(writeSnapshot(snapshot));
        revision.setPublishedBy(projectAccessService.requireCurrentUserId());
        revision.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(repository.save(revision));
    }

    public List<WorkflowRevisionSummaryResponse> list(String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return repository.findAllByWorkflowIdOrderByRevisionNoDesc(workflowId).stream()
            .map(this::toSummary)
            .toList();
    }

    public WorkflowRevisionResponse get(String workflowId, String revisionId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        return toResponse(findRevision(workflowId, revisionId));
    }

    @Transactional
    public WorkflowRevisionResponse retire(String workflowId, String revisionId) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        entityManager.lock(workflow, LockModeType.PESSIMISTIC_WRITE);
        WorkflowRevision revision = findRevision(workflowId, revisionId);
        if (!"published".equals(revision.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a published revision can be retired.");
        }
        revision.setStatus("retired");
        revision.setRetiredBy(projectAccessService.requireCurrentUserId());
        revision.setRetiredAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(repository.save(revision));
    }

    private WorkflowRevision findRevision(String workflowId, String revisionId) {
        return repository.findByWorkflowRevisionIdAndWorkflowId(revisionId, workflowId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private String writeSnapshot(WorkflowRevisionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow revision snapshot could not be serialized.", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow JSON is invalid.", exception);
        }
    }

    private WorkflowRevisionResponse toResponse(WorkflowRevision revision) {
        JsonNode snapshot;
        try {
            // Published mapping literals must round-trip without binary floating-point rounding or scale loss.
            snapshot = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(revision.getSnapshotJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored workflow revision snapshot is invalid.", exception);
        }
        return new WorkflowRevisionResponse(
            revision.getWorkflowRevisionId(), revision.getWorkflowId(), revision.getRevisionNo(),
            revision.getStatus(), revision.getSchemaVersion(), snapshot,
            revision.getPublishedBy(), revision.getPublishedAt(),
            revision.getRetiredBy(), revision.getRetiredAt()
        );
    }

    private WorkflowRevisionSummaryResponse toSummary(WorkflowRevision revision) {
        return new WorkflowRevisionSummaryResponse(
            revision.getWorkflowRevisionId(), revision.getWorkflowId(), revision.getRevisionNo(),
            revision.getStatus(), revision.getSchemaVersion(), revision.getPublishedBy(),
            revision.getPublishedAt(), revision.getRetiredBy(), revision.getRetiredAt()
        );
    }
}
