package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunEquipmentTimeCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunEquipmentTimeRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunEquipmentTimeResponse;
import org.myweb.flowmat.domain.production.application.RunEquipmentTimeService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A run's equipment running time and its cost (docs/domain/equipment-setup-cost.md ER1-ER7). Read: Project read; changes: write. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs/{productionRunId}/equipment-time")
public class RunEquipmentTimeController {

    private final RunEquipmentTimeService runEquipmentTimeService;

    @GetMapping
    public ApiResponse<RunEquipmentTimeResponse> times(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runEquipmentTimeService.times(productionRunId));
    }

    @PostMapping
    public ApiResponse<RunEquipmentTimeResponse> record(
        @PathVariable("productionRunId") String productionRunId,
        @RequestBody RunEquipmentTimeRequest request
    ) {
        return ApiResponse.ok(runEquipmentTimeService.record(productionRunId, request));
    }

    @PostMapping("/{runEquipmentTimeId}/cancel")
    public ApiResponse<RunEquipmentTimeResponse> cancel(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("runEquipmentTimeId") String runEquipmentTimeId,
        @RequestBody(required = false) RunEquipmentTimeCancelRequest request
    ) {
        return ApiResponse.ok(runEquipmentTimeService.cancel(productionRunId, runEquipmentTimeId, request));
    }

    /** A body that is not JSON, a requestId that is not a UUID or minutes that are not a number are the caller's mistake. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("requestId must be a UUID and runningMinutes a whole number."));
    }
}
