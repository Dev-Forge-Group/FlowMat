package org.myweb.flowmat.domain.workflow.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowCreateRequest;
import org.myweb.flowmat.domain.workflow.api.dto.request.WorkflowUpdateRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowCanvasResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowGraphChangesResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.GraphSyncService;
import org.myweb.flowmat.domain.workflow.collab.WorkflowSessionRegistry;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.myweb.flowmat.domain.workflow.application.WorkflowCanvasService;
import org.myweb.flowmat.domain.workflow.application.WorkflowService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowCanvasService workflowCanvasService;
    private final GraphSyncService graphSyncService;
    private final WorkflowSessionRegistry sessionRegistry;
    private final WorkflowAccessService workflowAccess;

    @GetMapping
    public ApiResponse<List<WorkflowResponse>> listWorkflows(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(workflowService.listWorkflows(projectId));
    }

    @PostMapping
    public ApiResponse<WorkflowResponse> createWorkflow(@Valid @RequestBody WorkflowCreateRequest request) {
        return ApiResponse.ok(workflowService.createWorkflow(request));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowResponse> getWorkflow(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(workflowService.getWorkflow(workflowId));
    }

    @PutMapping("/{workflowId}")
    public ApiResponse<WorkflowResponse> updateWorkflow(
        @PathVariable("workflowId") String workflowId,
        @RequestBody WorkflowUpdateRequest request
    ) {
        return ApiResponse.ok(workflowService.updateWorkflow(workflowId, request));
    }

    @DeleteMapping("/{workflowId}")
    public ApiResponse<Void> deleteWorkflow(@PathVariable("workflowId") String workflowId) {
        workflowService.deleteWorkflow(workflowId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{workflowId}/canvas")
    public ApiResponse<WorkflowCanvasResponse> getCanvas(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(workflowCanvasService.getCanvas(workflowId));
    }

    @GetMapping("/{workflowId}/graph-changes")
    public ApiResponse<WorkflowGraphChangesResponse> getGraphChanges(
        @PathVariable("workflowId") String workflowId,
        @RequestParam(value = "sinceSeq", defaultValue = "0") long sinceSeq
    ) {
        return ApiResponse.ok(graphSyncService.getChangesSince(workflowId, sinceSeq));
    }

    @GetMapping("/{workflowId}/presence")
    public ApiResponse<List<PresenceMessage>> getPresence(@PathVariable("workflowId") String workflowId) {
        workflowAccess.requireWorkflowReadAccess(workflowId);
        List<PresenceMessage> snapshot = sessionRegistry.listWorkflowSessions(workflowId).stream()
            .filter(session -> session.userId() != null)
            .map(session -> new PresenceMessage(
                session.editingProcessId() != null ? PresenceMessage.Type.NODE_EDITING : PresenceMessage.Type.JOIN,
                session.userId(),
                session.clientId(),
                workflowId,
                session.cursorX(),
                session.cursorY(),
                session.editingProcessId(),
                null,
                session.lastSeenAt()
            ))
            .toList();
        return ApiResponse.ok(snapshot);
    }
}
