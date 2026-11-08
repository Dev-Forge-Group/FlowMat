package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunInputAllocationRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunItemResponse;
import org.myweb.flowmat.domain.production.application.RunInputAllocationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Records one input over several LOTs, first-expiring first (docs/domain/lot-expiry.md "여러 LOT에 나눠 투입"). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs")
public class RunInputAllocationController {

    private final RunInputAllocationService runInputAllocationService;

    @PostMapping("/{productionRunId}/inputs/fefo")
    public ApiResponse<List<ProductionRunItemResponse>> allocate(
        @PathVariable("productionRunId") String productionRunId,
        @Valid @RequestBody RunInputAllocationRequest request
    ) {
        return ApiResponse.ok(runInputAllocationService.allocate(productionRunId, request));
    }
}
