package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.PlanPreviewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.PlanPreviewResponse;
import org.myweb.flowmat.domain.production.application.ProductionPlanService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Planning from a target (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7). A preview is read only:
 * project read access is enough, and nothing is reserved or written.
 */
@RestController
@RequiredArgsConstructor
public class ProductionPlanController {

    private final ProductionPlanService productionPlanService;

    @PostMapping("/workflows/{workflowId}/plan-previews")
    public ApiResponse<PlanPreviewResponse> preview(@PathVariable("workflowId") String workflowId,
        @Valid @RequestBody PlanPreviewRequest request) {
        return ApiResponse.ok(productionPlanService.preview(workflowId, request));
    }
}
