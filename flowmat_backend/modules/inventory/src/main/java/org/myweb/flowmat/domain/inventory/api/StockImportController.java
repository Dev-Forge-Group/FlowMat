package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.StockImportRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockImportResponse;
import org.myweb.flowmat.domain.inventory.application.StockImportService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Stock received from a spreadsheet (docs/domain/stock-import.md); all rows or none. */
@RestController
@RequiredArgsConstructor
public class StockImportController {

    private final StockImportService stockImportService;

    @PostMapping("/inventories/import")
    public ApiResponse<StockImportResponse> importStock(@Valid @RequestBody StockImportRequest request) {
        return ApiResponse.ok(stockImportService.importStock(request));
    }
}
