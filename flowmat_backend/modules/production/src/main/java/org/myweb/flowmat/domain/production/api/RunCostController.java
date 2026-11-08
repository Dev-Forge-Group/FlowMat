package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.RunByProductValueResponse;
import org.myweb.flowmat.domain.production.application.RunByProductValueService;
import org.myweb.flowmat.domain.production.api.dto.response.RunCostResponse;
import org.myweb.flowmat.domain.production.api.dto.response.RunWasteDisposalResponse;
import org.myweb.flowmat.domain.production.api.dto.response.RunMaterialUsageResponse;
import org.myweb.flowmat.domain.production.application.RunCostService;
import org.myweb.flowmat.domain.production.application.RunMaterialUsageService;
import org.myweb.flowmat.domain.production.application.RunWasteDisposalService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A run's material cost and its use against the BOM (docs/domain/material-cost.md "실행 재료비", "사용량 차이"). */
@RestController
@RequiredArgsConstructor
public class RunCostController {

    private final RunCostService runCostService;
    private final RunByProductValueService byProductValues;
    private final RunMaterialUsageService runMaterialUsageService;
    private final RunWasteDisposalService runWasteDisposalService;

    @GetMapping("/production-runs/{productionRunId}/cost")
    public ApiResponse<RunCostResponse> cost(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runCostService.cost(productionRunId));
    }

    @GetMapping("/production-runs/{productionRunId}/by-product-value")
    public ApiResponse<RunByProductValueResponse> byProductValue(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(byProductValues.value(productionRunId));
    }

    /** Separate from material cost and by-product value (docs/domain/bom-by-products.md WD5). */
    @GetMapping("/production-runs/{productionRunId}/waste-disposal-cost")
    public ApiResponse<RunWasteDisposalResponse> wasteDisposalCost(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runWasteDisposalService.cost(productionRunId));
    }

    @GetMapping("/production-runs/{productionRunId}/material-usage")
    public ApiResponse<RunMaterialUsageResponse> materialUsage(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runMaterialUsageService.usage(productionRunId));
    }
}
