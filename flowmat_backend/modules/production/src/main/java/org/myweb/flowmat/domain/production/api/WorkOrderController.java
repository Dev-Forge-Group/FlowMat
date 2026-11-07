package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderUpdateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderReadinessResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderResponse;
import org.myweb.flowmat.domain.production.application.WorkOrderReadinessService;
import org.myweb.flowmat.domain.production.application.WorkOrderService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/work-orders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final WorkOrderReadinessService workOrderReadinessService;

    @GetMapping
    public ApiResponse<List<WorkOrderResponse>> listWorkOrders(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(workOrderService.listWorkOrders(projectId));
    }

    /** Whether the order can run now: status, workflow, BOM and materials for what is still to produce. */
    @GetMapping("/{workOrderId}/readiness")
    public ApiResponse<WorkOrderReadinessResponse> getReadiness(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(workOrderReadinessService.check(workOrderId));
    }

    @PostMapping
    public ApiResponse<WorkOrderResponse> createWorkOrder(@Valid @RequestBody WorkOrderCreateRequest request) {
        return ApiResponse.ok(workOrderService.createWorkOrder(request));
    }

    @GetMapping("/{workOrderId}")
    public ApiResponse<WorkOrderResponse> getWorkOrder(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(workOrderService.getWorkOrder(workOrderId));
    }

    @PutMapping("/{workOrderId}")
    public ApiResponse<WorkOrderResponse> updateWorkOrder(
        @PathVariable("workOrderId") String workOrderId,
        @RequestBody WorkOrderUpdateRequest request
    ) {
        return ApiResponse.ok(workOrderService.updateWorkOrder(workOrderId, request));
    }

    @PostMapping("/{workOrderId}/approve")
    public ApiResponse<WorkOrderResponse> approveWorkOrder(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(workOrderService.approveWorkOrder(workOrderId));
    }

    @PostMapping("/{workOrderId}/cancel")
    public ApiResponse<WorkOrderResponse> cancelWorkOrder(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(workOrderService.cancelWorkOrder(workOrderId));
    }

    @PostMapping("/{workOrderId}/complete")
    public ApiResponse<WorkOrderResponse> completeWorkOrder(@PathVariable("workOrderId") String workOrderId) {
        return ApiResponse.ok(workOrderService.completeWorkOrder(workOrderId));
    }
}
