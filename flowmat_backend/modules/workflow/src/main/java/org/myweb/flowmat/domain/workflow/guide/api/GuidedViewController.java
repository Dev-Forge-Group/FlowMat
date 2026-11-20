package org.myweb.flowmat.domain.workflow.guide.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.guide.api.dto.request.GuidedViewOrderRequest;
import org.myweb.flowmat.domain.workflow.guide.api.dto.request.GuidedViewSaveRequest;
import org.myweb.flowmat.domain.workflow.guide.api.dto.response.GuidedViewResponse;
import org.myweb.flowmat.domain.workflow.guide.application.GuidedViewService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A workflow's guided views (docs/architecture/adr/ADR-037-guided-views.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/guided-views")
public class GuidedViewController {

    private final GuidedViewService service;

    @GetMapping
    public ApiResponse<List<GuidedViewResponse>> list(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(service.list(workflowId));
    }

    @PostMapping
    public ApiResponse<GuidedViewResponse> create(@PathVariable("workflowId") String workflowId,
                                                  @RequestBody GuidedViewSaveRequest request) {
        return ApiResponse.ok(service.create(workflowId, request));
    }

    @PutMapping("/{guidedViewId}")
    public ApiResponse<GuidedViewResponse> update(@PathVariable("workflowId") String workflowId,
                                                  @PathVariable("guidedViewId") String guidedViewId,
                                                  @RequestBody GuidedViewSaveRequest request) {
        return ApiResponse.ok(service.update(workflowId, guidedViewId, request));
    }

    @DeleteMapping("/{guidedViewId}")
    public ApiResponse<Void> delete(@PathVariable("workflowId") String workflowId,
                                    @PathVariable("guidedViewId") String guidedViewId) {
        service.delete(workflowId, guidedViewId);
        return ApiResponse.ok(null);
    }

    /** Every chapter's id in the new order. */
    @PutMapping("/order")
    public ApiResponse<List<GuidedViewResponse>> reorder(@PathVariable("workflowId") String workflowId,
                                                         @RequestBody GuidedViewOrderRequest request) {
        return ApiResponse.ok(service.reorder(workflowId, request));
    }
}
