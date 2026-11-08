package org.myweb.flowmat.domain.workflow.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionSummaryResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowRevisionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/revisions")
public class WorkflowRevisionController {

    private final WorkflowRevisionService service;

    @PostMapping
    public ApiResponse<WorkflowRevisionResponse> publish(@PathVariable String workflowId) {
        return ApiResponse.ok(service.publish(workflowId));
    }

    @GetMapping
    public ApiResponse<List<WorkflowRevisionSummaryResponse>> list(@PathVariable String workflowId) {
        return ApiResponse.ok(service.list(workflowId));
    }

    @GetMapping("/{revisionId}")
    public ApiResponse<WorkflowRevisionResponse> get(@PathVariable String workflowId, @PathVariable String revisionId) {
        return ApiResponse.ok(service.get(workflowId, revisionId));
    }

    @PostMapping("/{revisionId}/retire")
    public ApiResponse<WorkflowRevisionResponse> retire(@PathVariable String workflowId, @PathVariable String revisionId) {
        return ApiResponse.ok(service.retire(workflowId, revisionId));
    }
}
