package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRunningComparisonResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderRunningComparisonService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A work order's planned running time beside its runs' recorded running time (docs/domain/equipment-setup-cost.md ER8). Project read. */
@RestController
@RequiredArgsConstructor
public class WorkOrderRunningComparisonController {
    private final WorkOrderRunningComparisonService service;

    @GetMapping("/work-orders/{workOrderId}/running-comparison")
    public ApiResponse<WorkOrderRunningComparisonResponse> compare(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(service.compare(workOrderId));
    }
}
