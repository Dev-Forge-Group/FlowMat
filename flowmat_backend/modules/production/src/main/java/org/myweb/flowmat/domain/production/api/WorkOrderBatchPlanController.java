package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderBatchPlanRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderBatchPlanResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderSequenceResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderPlanService;
import org.myweb.flowmat.domain.production.application.WorkOrderSequenceService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan suggestions for several work orders at once (docs/domain/equipment-schedule.md P8-P12) and the order with the fewest
 * changeovers to plan them in (P13-P16). Project read; read only.
 */
@RestController
@RequiredArgsConstructor
public class WorkOrderBatchPlanController {

    private final WorkOrderPlanService workOrderPlanService;
    private final WorkOrderSequenceService workOrderSequenceService;

    @PostMapping("/work-orders/plan-suggestions")
    public ApiResponse<WorkOrderBatchPlanResponse> suggestAll(@RequestBody WorkOrderBatchPlanRequest request) {
        return ApiResponse.ok(workOrderPlanService.suggestAll(request));
    }

    @PostMapping("/work-orders/plan-order")
    public ApiResponse<WorkOrderSequenceResponse> suggestOrder(@RequestBody WorkOrderBatchPlanRequest request) {
        return ApiResponse.ok(workOrderSequenceService.suggestOrder(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("Send projectId, workOrderIds as a list of work order ids and from as text."));
    }
}
