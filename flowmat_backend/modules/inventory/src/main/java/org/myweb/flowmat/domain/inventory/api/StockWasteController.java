package org.myweb.flowmat.domain.inventory.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockWasteResponse;
import org.myweb.flowmat.domain.inventory.application.StockWasteService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock lost in the last days, by why (docs/domain/stock-analysis.md "폐기·손실"). */
@RestController
@RequiredArgsConstructor
public class StockWasteController {

    private final StockWasteService stockWasteService;

    @GetMapping("/stock-waste")
    public ApiResponse<StockWasteResponse> waste(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "days", required = false) Integer days
    ) {
        return ApiResponse.ok(stockWasteService.waste(projectId, days));
    }
}
