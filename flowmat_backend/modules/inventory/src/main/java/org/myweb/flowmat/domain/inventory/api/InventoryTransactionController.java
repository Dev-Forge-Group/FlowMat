package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryReversalRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransactionCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionPageResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryTransactionSearchService;
import org.myweb.flowmat.domain.inventory.application.InventoryTransactionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inventory-transactions")
public class InventoryTransactionController {

    private final InventoryTransactionService inventoryTransactionService;
    private final InventoryTransactionSearchService inventoryTransactionSearchService;

    @GetMapping
    public ApiResponse<List<InventoryTransactionResponse>> listTransactions(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "inventoryId", required = false) String inventoryId
    ) {
        return ApiResponse.ok(inventoryTransactionService.listTransactions(projectId, inventoryId));
    }

    /** The project's movements filtered and paged on the server, newest first (docs/domain/stock-ledger.md). */
    @GetMapping("/search")
    public ApiResponse<InventoryTransactionPageResponse> searchTransactions(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "type", required = false) String type,
        @RequestParam(value = "itemId", required = false) String itemId,
        @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @RequestParam(value = "text", required = false) String text,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "cursor", required = false) String cursor
    ) {
        return ApiResponse.ok(inventoryTransactionSearchService.search(projectId, type, itemId, from, to, text, limit, cursor));
    }

    @PostMapping
    public ApiResponse<InventoryTransactionResponse> createTransaction(
        @Valid @RequestBody InventoryTransactionCreateRequest request
    ) {
        return ApiResponse.ok(inventoryTransactionService.createTransaction(request));
    }

    @GetMapping("/{inventoryTransactionId}")
    public ApiResponse<InventoryTransactionResponse> getTransaction(
        @PathVariable("inventoryTransactionId") String inventoryTransactionId
    ) {
        return ApiResponse.ok(inventoryTransactionService.getTransaction(inventoryTransactionId));
    }

    @PostMapping("/{inventoryTransactionId}/reversal")
    public ApiResponse<InventoryTransactionResponse> reverseTransaction(
        @PathVariable("inventoryTransactionId") String inventoryTransactionId,
        @Valid @RequestBody InventoryReversalRequest request
    ) {
        return ApiResponse.ok(inventoryTransactionService.reverseTransaction(inventoryTransactionId, request));
    }
}
