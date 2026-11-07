package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransferResponse;
import org.myweb.flowmat.domain.inventory.application.InventoryTransferService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Moving stock to another place (docs/domain/stock-transfer.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inventory-transfers")
public class InventoryTransferController {

    private final InventoryTransferService inventoryTransferService;

    @PostMapping
    public ApiResponse<InventoryTransferResponse> transfer(@Valid @RequestBody InventoryTransferRequest request) {
        return ApiResponse.ok(inventoryTransferService.transfer(request));
    }
}
