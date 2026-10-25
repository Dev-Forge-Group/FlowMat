package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanAssignRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanRecordRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryCountPlanResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryCountPlanService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inventory-count-plans")
public class InventoryCountPlanController {
    private final InventoryCountPlanService service;
    @GetMapping
    public ApiResponse<List<InventoryCountPlanResponse>> list(@RequestParam String projectId) { return ApiResponse.ok(service.list(projectId)); }
    @GetMapping("/{planId}")
    public ApiResponse<InventoryCountPlanResponse> get(@PathVariable String planId) { return ApiResponse.ok(service.get(planId)); }
    @PostMapping
    public ApiResponse<InventoryCountPlanResponse> create(@Valid @RequestBody InventoryCountPlanCreateRequest request) { return ApiResponse.ok(service.create(request)); }
    @PutMapping("/{planId}/lines/{lineId}")
    public ApiResponse<InventoryCountPlanResponse> record(@PathVariable String planId, @PathVariable String lineId,
        @Valid @RequestBody InventoryCountPlanRecordRequest request) { return ApiResponse.ok(service.record(planId, lineId, request)); }
    @PostMapping("/{planId}/lines/{lineId}/recount")
    public ApiResponse<InventoryCountPlanResponse> recount(@PathVariable String planId, @PathVariable String lineId) { return ApiResponse.ok(service.recount(planId, lineId)); }
    @PostMapping("/{planId}/assignee")
    public ApiResponse<InventoryCountPlanResponse> assign(@PathVariable String planId,
        @RequestBody(required = false) InventoryCountPlanAssignRequest request) { return ApiResponse.ok(service.assign(planId, request)); }
    @PostMapping("/{planId}/submit")
    public ApiResponse<InventoryCountPlanResponse> submit(@PathVariable String planId) {
        InventoryCountPlanResponse result = service.submit(planId);
        if ("recount_required".equals(result.status())) throw new BusinessException(ErrorCode.CONFLICT,
            "Stock changed in this plan. Recount the flagged rows before submitting again.");
        return ApiResponse.ok(result);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("requestId must be a UUID; countedQuantity and expectedEntryVersion must be valid numbers."));
    }
}
