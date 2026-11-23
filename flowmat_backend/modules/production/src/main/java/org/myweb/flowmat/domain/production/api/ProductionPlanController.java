package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.PlanConfirmRequest;
import org.myweb.flowmat.domain.production.api.dto.request.PlanPreviewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.PlanActualResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanConfirmationResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanConfirmationSummaryResponse;
import org.myweb.flowmat.domain.production.api.dto.response.PlanPreviewResponse;
import org.myweb.flowmat.domain.production.application.PlanActualService;
import org.myweb.flowmat.domain.production.application.ProductionPlanConfirmationService;
import org.myweb.flowmat.domain.production.application.ProductionPlanService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ProductionPlanConfirmationService confirmationService;
    private final PlanActualService planActualService;

    @PostMapping("/workflows/{workflowId}/plan-previews")
    public ApiResponse<PlanPreviewResponse> preview(@PathVariable("workflowId") String workflowId,
        @Valid @RequestBody PlanPreviewRequest request) {
        return ApiResponse.ok(productionPlanService.preview(workflowId, request));
    }

    /** A draft work order and a draft purchase order for the shortages; the project's write access, once per requestId. */
    @PostMapping("/workflows/{workflowId}/plan-confirmations")
    public ApiResponse<PlanConfirmationResponse> confirm(@PathVariable("workflowId") String workflowId,
        @Valid @RequestBody PlanConfirmRequest request) {
        return ApiResponse.ok(confirmationService.confirm(workflowId, request));
    }

    /** The workflow's confirmed plans, newest first; project read access. */
    @GetMapping("/workflows/{workflowId}/plan-confirmations")
    public ApiResponse<List<PlanConfirmationSummaryResponse>> confirmations(@PathVariable("workflowId") String workflowId) {
        return ApiResponse.ok(planActualService.confirmations(workflowId));
    }

    /** A work order's confirmed plan against its finished runs (docs/domain/plan-vs-actual.md); project read access. */
    @GetMapping("/work-orders/{workOrderId}/plan-actuals")
    public ApiResponse<PlanActualResponse> planActuals(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(planActualService.compare(workOrderId));
    }
}
