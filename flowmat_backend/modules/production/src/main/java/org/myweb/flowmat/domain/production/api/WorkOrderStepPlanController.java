package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderStepPlanRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborComparisonResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderStepPlanService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A work order's planned worker minutes by process (docs/domain/worker-calendar.md LB23). Changes: Project write. */
@RestController
@RequiredArgsConstructor
public class WorkOrderStepPlanController {

    private final WorkOrderStepPlanService service;

    @PutMapping("/work-orders/{workOrderId}/step-plan")
    public ApiResponse<WorkOrderLaborComparisonResponse> set(@PathVariable("workOrderId") String workOrderId,
                                                             @RequestBody(required = false) WorkOrderStepPlanRequest request) {
        return ApiResponse.ok(service.set(workOrderId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send steps as a list of process ids, each with whole planned minutes."));
    }
}
