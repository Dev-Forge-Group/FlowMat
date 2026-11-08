package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderSetupComparisonResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderSetupComparisonService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A work order's planned setup beside its runs' actual setups (docs/domain/equipment-setup-cost.md AS11). Project read. */
@RestController
@RequiredArgsConstructor
public class WorkOrderSetupComparisonController {
    private final WorkOrderSetupComparisonService service;

    @GetMapping("/work-orders/{workOrderId}/setup-comparison")
    public ApiResponse<WorkOrderSetupComparisonResponse> compare(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(service.compare(workOrderId));
    }
}
