package org.myweb.flowmat.domain.inventory.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockMovementAnalysisResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockTransferAnalysisResponse;
import org.myweb.flowmat.domain.inventory.application.StockMovementAnalysisService;
import org.myweb.flowmat.domain.inventory.application.StockTransferAnalysisService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumption, days of cover and idle stock per item (docs/domain/stock-analysis.md). Read only. */
@RestController
@RequiredArgsConstructor
public class StockAnalysisController {

    private final StockMovementAnalysisService stockMovementAnalysisService;
    private final StockTransferAnalysisService stockTransferAnalysisService;

    @GetMapping("/stock-analysis")
    public ApiResponse<StockMovementAnalysisResponse> analyse(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "days", required = false) Integer days,
        @RequestParam(value = "location", required = false) String location
    ) {
        return ApiResponse.ok(stockMovementAnalysisService.analyse(projectId, days, location));
    }

    /** Stock moved between places in the last days, per route (docs/domain/stock-analysis.md "위치 간 이동"). */
    @GetMapping("/stock-analysis/transfers")
    public ApiResponse<StockTransferAnalysisResponse> transfers(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "days", required = false) Integer days
    ) {
        return ApiResponse.ok(stockTransferAnalysisService.transfers(projectId, days));
    }
}
