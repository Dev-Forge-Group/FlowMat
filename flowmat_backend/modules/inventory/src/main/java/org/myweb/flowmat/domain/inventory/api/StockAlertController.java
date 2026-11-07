package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import org.myweb.flowmat.domain.inventory.api.dto.request.PlaceSafetyStockRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.PlaceSafetyStockResponse;
import org.myweb.flowmat.domain.inventory.application.PlaceSafetyStockService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.ReorderLineResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockAlertResponse;
import org.myweb.flowmat.domain.inventory.application.StockAlertService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock alerts (docs/domain/stock-alert.md). Alerts open and close with the stock itself; what is set here is the safety
 * stock of an item at a place (PS2).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/stock-alerts")
public class StockAlertController {

    private final StockAlertService stockAlertService;
    private final PlaceSafetyStockService placeSafetyStockService;

    @GetMapping
    public ApiResponse<List<StockAlertResponse>> listAlerts(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "openOnly", defaultValue = "true") boolean openOnly
    ) {
        return ApiResponse.ok(stockAlertService.listAlerts(projectId, openOnly));
    }

    /**
     * Items below their safety stock over all their records, or with {@code location} below their safety stock at that place
     * over its stock and the stock of the places inside it (PS4); biggest shortfall first.
     */
    @GetMapping("/reorder")
    public ApiResponse<List<ReorderLineResponse>> reorderList(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "location", required = false) String location
    ) {
        return ApiResponse.ok(location == null || location.isBlank()
            ? stockAlertService.reorderList(projectId)
            : placeSafetyStockService.reorderAt(projectId, location));
    }

    /** The project's safety stocks per item and place (PS3). */
    @GetMapping("/place-safety-stocks")
    public ApiResponse<List<PlaceSafetyStockResponse>> placeSafetyStocks(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(placeSafetyStockService.list(projectId));
    }

    /** Sets an item's safety stock at a place, or removes it with none or zero (PS2); answers the project's list. */
    @PutMapping("/place-safety-stocks")
    public ApiResponse<List<PlaceSafetyStockResponse>> setPlaceSafetyStock(@Valid @RequestBody PlaceSafetyStockRequest request) {
        return ApiResponse.ok(placeSafetyStockService.set(request));
    }

    /** A body that is not JSON, or a quantity that is not a number, is the caller's mistake rather than a server error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("The request body is not valid JSON, or safetyStockQty is not a number."));
    }
}
