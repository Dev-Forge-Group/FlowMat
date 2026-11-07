package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCrewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderCrewResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderCrewService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The people a work order needs (docs/domain/worker-calendar.md WC12). Read with project read, set with project write. */
@RestController
@RequiredArgsConstructor
public class WorkOrderCrewController {

    private final WorkOrderCrewService service;

    @GetMapping("/work-orders/{workOrderId}/crew")
    public ApiResponse<WorkOrderCrewResponse> crew(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(service.crew(workOrderId));
    }

    @PutMapping("/work-orders/{workOrderId}/crew")
    public ApiResponse<WorkOrderCrewResponse> set(@PathVariable("workOrderId") String workOrderId,
                                                  @RequestBody(required = false) WorkOrderCrewRequest request) {
        return ApiResponse.ok(service.set(workOrderId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send userIds as a list of user ids, or members as a list of userId and sharePercent."));
    }
}
