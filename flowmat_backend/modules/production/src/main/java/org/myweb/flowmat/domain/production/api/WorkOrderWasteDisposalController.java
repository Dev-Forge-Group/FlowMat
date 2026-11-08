package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderWasteDisposalResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderWasteDisposalService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A work order's waste disposal cost estimate (docs/domain/bom-by-products.md WD8). Project read. */
@RestController
@RequiredArgsConstructor
public class WorkOrderWasteDisposalController {
    private final WorkOrderWasteDisposalService service;

    @GetMapping("/work-orders/{workOrderId}/waste-disposal-estimate")
    public ApiResponse<WorkOrderWasteDisposalResponse> estimate(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(service.estimate(workOrderId));
    }
}
