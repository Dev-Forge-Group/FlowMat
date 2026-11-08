package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.AllocatedStockTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransferResponse;
import org.myweb.flowmat.domain.inventory.application.AllocatedStockTransferService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/allocated-stock-transfers")
public class AllocatedStockTransferController {
    private final AllocatedStockTransferService service;
    @PostMapping
    public ApiResponse<InventoryTransferResponse> transfer(@Valid @RequestBody AllocatedStockTransferRequest request) {
        return ApiResponse.ok(service.transfer(request));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("requestId must be a UUID and quantity must be a valid decimal."));
    }
}
