package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderRescheduleRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRescheduleResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRescheduleResult;
import org.myweb.flowmat.domain.production.application.WorkOrderRescheduleService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/work-orders/{workOrderId}/reschedules")
public class WorkOrderRescheduleController {
    private final WorkOrderRescheduleService service;
    @GetMapping
    public ApiResponse<List<WorkOrderRescheduleResponse>> history(@PathVariable String workOrderId) {
        return ApiResponse.ok(service.history(workOrderId));
    }
    @PostMapping
    public ApiResponse<WorkOrderRescheduleResult> reschedule(@PathVariable String workOrderId,
        @Valid @RequestBody WorkOrderRescheduleRequest request) {
        return ApiResponse.ok(service.reschedule(workOrderId, request));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidInput(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(
            "requestId must be a UUID; plannedStartAt, plannedEndAt and expected planned dates must be ISO timestamps or null."));
    }
}
