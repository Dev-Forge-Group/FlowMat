package org.myweb.flowmat.domain.catalog.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemImportRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemCostChangeResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemImportResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemResponse;
import org.myweb.flowmat.domain.catalog.application.ItemCostHistoryService;
import org.myweb.flowmat.domain.catalog.application.ItemImportService;
import org.myweb.flowmat.domain.catalog.application.ItemService;
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
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;
    private final ItemCostHistoryService itemCostHistoryService;
    private final ItemImportService itemImportService;

    @GetMapping
    public ApiResponse<List<ItemResponse>> listItems(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(itemService.listItems(projectId));
    }

    @PostMapping
    public ApiResponse<ItemResponse> createItem(@Valid @RequestBody ItemCreateRequest request) {
        return ApiResponse.ok(itemService.createItem(request));
    }

    /** Creates and updates items from a spreadsheet by item code (docs/domain/item-import.md); all rows or none. */
    @PostMapping("/import")
    public ApiResponse<ItemImportResponse> importItems(@Valid @RequestBody ItemImportRequest request) {
        return ApiResponse.ok(itemImportService.importItems(request));
    }

    @GetMapping("/{itemId}")
    public ApiResponse<ItemResponse> getItem(@PathVariable("itemId") String itemId) {
        return ApiResponse.ok(itemService.getItem(itemId));
    }

    /** Each change of the item's unit cost, newest first (docs/domain/material-cost.md "단가 이력"). */
    @GetMapping("/{itemId}/cost-history")
    public ApiResponse<List<ItemCostChangeResponse>> costHistory(@PathVariable("itemId") String itemId) {
        return ApiResponse.ok(itemCostHistoryService.history(itemId));
    }

    @PutMapping("/{itemId}")
    public ApiResponse<ItemResponse> updateItem(
        @PathVariable("itemId") String itemId,
        @Valid @RequestBody ItemUpdateRequest request
    ) {
        return ApiResponse.ok(itemService.updateItem(itemId, request));
    }

    @DeleteMapping("/{itemId}")
    public ApiResponse<Void> deleteItem(@PathVariable("itemId") String itemId) {
        itemService.deleteItem(itemId);
        return ApiResponse.ok(null);
    }
}
