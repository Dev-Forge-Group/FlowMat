package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunLaborCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunLaborRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunLaborResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborComparisonResponse;
import org.myweb.flowmat.domain.production.application.RunLaborService;
import org.myweb.flowmat.domain.production.application.WorkOrderLaborComparisonService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who worked on a run and for how long, and a work order's crew plan beside it (docs/domain/worker-calendar.md LB1-LB6). Read:
 * Project read; changes: write.
 */
@RestController
@RequiredArgsConstructor
public class RunLaborController {

    private final RunLaborService runLaborService;
    private final WorkOrderLaborComparisonService comparisonService;

    @GetMapping("/production-runs/{productionRunId}/labor")
    public ApiResponse<RunLaborResponse> labor(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runLaborService.labor(productionRunId));
    }

    @PostMapping("/production-runs/{productionRunId}/labor")
    public ApiResponse<RunLaborResponse> record(
        @PathVariable("productionRunId") String productionRunId,
        @RequestBody RunLaborRequest request
    ) {
        return ApiResponse.ok(runLaborService.record(productionRunId, request));
    }

    @PostMapping("/production-runs/{productionRunId}/labor/{runLaborId}/cancel")
    public ApiResponse<RunLaborResponse> cancel(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("runLaborId") String runLaborId,
        @RequestBody(required = false) RunLaborCancelRequest request
    ) {
        return ApiResponse.ok(runLaborService.cancel(productionRunId, runLaborId, request));
    }

    @GetMapping("/work-orders/{workOrderId}/labor-comparison")
    public ApiResponse<WorkOrderLaborComparisonResponse> compare(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(comparisonService.compare(workOrderId));
    }

    /** A body that is not JSON, a requestId that is not a UUID or minutes that are not a whole number are the caller's mistake. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("requestId must be a UUID and workedMinutes a whole number."));
    }
}
