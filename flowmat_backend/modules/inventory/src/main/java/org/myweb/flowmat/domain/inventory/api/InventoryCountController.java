package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryCountHistoryResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryCountResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryCountHistoryService;
import org.myweb.flowmat.domain.inventory.application.InventoryCountService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock counts (docs/domain/stock-count.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inventory-counts")
public class InventoryCountController {

    private final InventoryCountService inventoryCountService;
    private final InventoryCountHistoryService inventoryCountHistoryService;

    @PostMapping
    public ApiResponse<InventoryCountResponse> count(@Valid @RequestBody InventoryCountRequest request) {
        return ApiResponse.ok(inventoryCountService.count(request));
    }

    /** The latest counts and the records each changed, newest first. */
    @GetMapping
    public ApiResponse<List<InventoryCountHistoryResponse>> history(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(inventoryCountHistoryService.history(projectId));
    }
}
