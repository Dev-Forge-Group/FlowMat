package org.myweb.flowmat.domain.workforce.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerLaborRateRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerLaborRateResponse;
import org.myweb.flowmat.domain.workforce.application.WorkerLaborRateService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A worker's hourly rates in a project, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/workers/{userId}/labor-rates")
public class WorkerLaborRateController {

    private final WorkerLaborRateService service;

    @GetMapping
    public ApiResponse<WorkerLaborRateResponse> rates(@PathVariable("projectId") String projectId,
                                                      @PathVariable("userId") String userId) {
        return ApiResponse.ok(service.rates(projectId, userId));
    }

    @PutMapping
    public ApiResponse<WorkerLaborRateResponse> setRate(@PathVariable("projectId") String projectId,
                                                        @PathVariable("userId") String userId,
                                                        @RequestBody(required = false) WorkerLaborRateRequest request) {
        return ApiResponse.ok(service.setRate(projectId, userId, request));
    }

    @DeleteMapping("/{rateId}")
    public ApiResponse<WorkerLaborRateResponse> removeRate(@PathVariable("projectId") String projectId,
                                                           @PathVariable("userId") String userId,
                                                           @PathVariable("rateId") String rateId) {
        return ApiResponse.ok(service.removeRate(projectId, userId, rateId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send the rate as JSON, with hourlyRate as a number or null."));
    }
}
