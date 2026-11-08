package org.myweb.flowmat.domain.workflow.annotation.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationBatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationCreateRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.request.CanvasAnnotationPatchRequest;
import org.myweb.flowmat.domain.workflow.annotation.api.dto.response.CanvasAnnotationResponse;
import org.myweb.flowmat.domain.workflow.annotation.application.CanvasAnnotationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/annotations")
public class CanvasAnnotationController {

    private final CanvasAnnotationService canvasAnnotationService;

    @GetMapping
    public ApiResponse<List<CanvasAnnotationResponse>> list(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(canvasAnnotationService.list(workflowId));
    }

    @PostMapping
    public ApiResponse<CanvasAnnotationResponse> create(
        @PathVariable("workflowId") String workflowId,
        @Valid @RequestBody CanvasAnnotationCreateRequest request
    ) {
        return ApiResponse.ok(canvasAnnotationService.create(workflowId, request));
    }

    @PatchMapping("/{annotationId}")
    public ApiResponse<CanvasAnnotationResponse> patch(
        @PathVariable("workflowId") String workflowId,
        @PathVariable("annotationId") String annotationId,
        @RequestBody CanvasAnnotationPatchRequest request
    ) {
        return ApiResponse.ok(canvasAnnotationService.patch(workflowId, annotationId, request));
    }

    @DeleteMapping("/{annotationId}")
    public ApiResponse<Void> delete(
        @PathVariable("workflowId") String workflowId,
        @PathVariable("annotationId") String annotationId
    ) {
        canvasAnnotationService.delete(workflowId, annotationId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/batch")
    public ApiResponse<List<CanvasAnnotationResponse>> batch(
        @PathVariable("workflowId") String workflowId,
        @Valid @RequestBody CanvasAnnotationBatchRequest request
    ) {
        return ApiResponse.ok(canvasAnnotationService.batchUpdate(workflowId, request));
    }
}
