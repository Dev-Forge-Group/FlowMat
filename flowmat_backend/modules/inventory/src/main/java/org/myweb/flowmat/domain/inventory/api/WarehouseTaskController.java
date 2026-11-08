package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.PickListRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.WarehouseTaskAssignRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.WarehouseTaskCancelRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.WarehouseTaskCompleteRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.WarehouseTaskCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.PickListResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.WarehouseTaskResponse;
import org.myweb.flowmat.domain.inventory.application.WarehouseTaskService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Putaway and pick tasks (docs/domain/warehouse-task.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/warehouse-tasks")
public class WarehouseTaskController {

    private final WarehouseTaskService warehouseTaskService;

    @GetMapping
    public ApiResponse<List<WarehouseTaskResponse>> list(
        @RequestParam String projectId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String workOrderId,
        @RequestParam(required = false) String assignedTo
    ) {
        return ApiResponse.ok(warehouseTaskService.list(projectId, status, workOrderId, assignedTo));
    }

    @PostMapping
    public ApiResponse<WarehouseTaskResponse> create(@Valid @RequestBody WarehouseTaskCreateRequest request) {
        return ApiResponse.ok(warehouseTaskService.create(request));
    }

    @PostMapping("/pick-list")
    public ApiResponse<PickListResponse> pickList(@Valid @RequestBody PickListRequest request) {
        return ApiResponse.ok(warehouseTaskService.pickList(request));
    }

    /** Does the task; with a smaller quantity only that much moves and the rest stays open (W6). */
    @PostMapping("/{taskId}/complete")
    public ApiResponse<WarehouseTaskResponse> complete(
        @PathVariable String taskId,
        @Valid @RequestBody(required = false) WarehouseTaskCompleteRequest request
    ) {
        return ApiResponse.ok(warehouseTaskService.complete(taskId, request == null ? null : request.quantity(),
            request == null ? null : request.expectedToLocation()));
    }

    /** Who should do an open task (docs/domain/warehouse-task.md W7). */
    @PutMapping("/{taskId}/assignee")
    public ApiResponse<WarehouseTaskResponse> assign(@PathVariable String taskId, @Valid @RequestBody WarehouseTaskAssignRequest request) {
        return ApiResponse.ok(warehouseTaskService.assign(taskId, request.assignedTo()));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<WarehouseTaskResponse> cancel(@PathVariable String taskId, @Valid @RequestBody WarehouseTaskCancelRequest request) {
        return ApiResponse.ok(warehouseTaskService.cancel(taskId, request.reason()));
    }
}
