package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.StockAllocationRequest;
import org.myweb.flowmat.domain.production.api.dto.response.StockAllocationResponse;
import org.myweb.flowmat.domain.production.application.StockAllocationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stock allocated to a work order (docs/domain/stock-allocation.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/work-orders/{workOrderId}/allocations")
public class StockAllocationController {

    private final StockAllocationService stockAllocationService;

    @GetMapping
    public ApiResponse<StockAllocationResponse> list(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(stockAllocationService.list(workOrderId));
    }

    @PostMapping
    public ApiResponse<StockAllocationResponse> allocate(
        @PathVariable("workOrderId") String workOrderId,
        @RequestBody(required = false) StockAllocationRequest request
    ) {
        return ApiResponse.ok(stockAllocationService.allocate(workOrderId, request));
    }

    @PostMapping("/{allocationId}/release")
    public ApiResponse<StockAllocationResponse> release(
        @PathVariable("workOrderId") String workOrderId,
        @PathVariable("allocationId") String allocationId
    ) {
        return ApiResponse.ok(stockAllocationService.release(workOrderId, allocationId));
    }

    @PostMapping("/release")
    public ApiResponse<StockAllocationResponse> releaseAll(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(stockAllocationService.releaseAll(workOrderId));
    }
}
