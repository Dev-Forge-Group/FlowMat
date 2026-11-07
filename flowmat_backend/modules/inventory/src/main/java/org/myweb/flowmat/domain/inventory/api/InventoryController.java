package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/inventories")
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public ApiResponse<List<InventoryResponse>> listInventories(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(inventoryService.listInventories(projectId));
    }

    @PostMapping
    public ApiResponse<InventoryResponse> createInventory(@Valid @RequestBody InventoryAdjustRequest request) {
        return ApiResponse.ok(inventoryService.createInventory(request));
    }

    @GetMapping("/{inventoryId}")
    public ApiResponse<InventoryResponse> getInventory(@PathVariable("inventoryId") String inventoryId) {
        return ApiResponse.ok(inventoryService.getInventory(inventoryId));
    }

    @PutMapping("/{inventoryId}")
    public ApiResponse<InventoryResponse> updateInventory(
        @PathVariable("inventoryId") String inventoryId,
        @Valid @RequestBody InventoryAdjustRequest request
    ) {
        return ApiResponse.ok(inventoryService.updateInventory(inventoryId, request));
    }

    @DeleteMapping("/{inventoryId}")
    public ApiResponse<Void> deleteInventory(@PathVariable("inventoryId") String inventoryId) {
        inventoryService.deleteInventory(inventoryId);
        return ApiResponse.ok(null);
    }
}
