package org.myweb.flowmat.domain.workflow.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectWorkflowCommand;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowUpdateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowServiceImpl implements WorkflowService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";

    private final WorkflowRepository workflowRepository;
    private final ProjectWorkflowCommand projectWorkflows;
    private final IdGenerator idGenerator;
    private final GraphSyncService graphSyncService;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccessService workflowAccess;

    @Override
    public List<WorkflowResponse> listWorkflows(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        return workflowRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED).stream()
            .map(WorkflowServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public WorkflowResponse createWorkflow(WorkflowCreateRequest request) {
        projectAccessService.requireProjectWriteAccess(request.projectId());
        // The check found the project by this exact id (docs/architecture/adr/ADR-018-entity-boundary.md R2).
        String projectId = request.projectId();

        Workflow workflow = new Workflow();
        workflow.setWorkflowId(idGenerator.generate());
        workflow.setProjectId(projectId);
        workflow.setWorkflowName(request.workflowName().trim());
        workflow.setWorkflowDesc(trimToNull(request.workflowDesc()));
        workflow.setWorkflowType(defaultIfBlank(request.workflowType(), "main"));
        workflow.setWorkflowStatus("active");
        workflow.setCanvasSnapshot("{}");
        workflow.setSimulationConfig("{}");
        workflow.setLockedYn("N");
        workflow.setDeletedYn(NOT_DELETED);

        Workflow savedWorkflow = workflowRepository.save(workflow);
        projectWorkflows.workflowAdded(projectId, savedWorkflow.getWorkflowId());
        return toResponse(savedWorkflow);
    }

    @Override
    public WorkflowResponse getWorkflow(String workflowId) {
        return toResponse(workflowAccess.requireWorkflowReadAccess(workflowId));
    }

    @Override
    @Transactional
    public WorkflowResponse updateWorkflow(String workflowId, WorkflowUpdateRequest request) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        if (hasText(request.workflowName())) {
            workflow.setWorkflowName(request.workflowName().trim());
        }
        if (request.workflowDesc() != null) {
            workflow.setWorkflowDesc(trimToNull(request.workflowDesc()));
        }
        if (hasText(request.workflowType())) {
            workflow.setWorkflowType(request.workflowType().trim().toLowerCase());
        }
        if (hasText(request.workflowStatus())) {
            workflow.setWorkflowStatus(request.workflowStatus().trim().toLowerCase());
        }
        Workflow saved = workflowRepository.save(workflow);
        graphSyncService.broadcast(Type.WORKFLOW_UPDATED, saved.getWorkflowId(), saved.getWorkflowId());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteWorkflow(String workflowId) {
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(workflowId);
        workflow.setDeletedYn(DELETED);
        workflow.setWorkflowStatus("deleted");
        workflowRepository.save(workflow);

        projectAccessService.requireProjectWriteAccess(workflow.getProjectId());
        projectWorkflows.workflowRemoved(workflow.getProjectId(), workflow.getWorkflowId());
    }

    private static WorkflowResponse toResponse(Workflow workflow) {
        return new WorkflowResponse(
            workflow.getWorkflowId(),
            workflow.getProjectId(),
            workflow.getWorkflowName(),
            workflow.getWorkflowDesc(),
            workflow.getWorkflowType(),
            workflow.getWorkflowStatus()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }
}
