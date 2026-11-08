package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunSetupCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunSetupRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunSetupCostResponse;
import org.myweb.flowmat.domain.production.application.RunSetupService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A run's actual setups and their cost (docs/domain/equipment-setup-cost.md AS1-AS6). Read: Project read; changes: write. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs/{productionRunId}/setups")
public class RunSetupController {

    private final RunSetupService runSetupService;

    @GetMapping
    public ApiResponse<RunSetupCostResponse> setups(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runSetupService.setups(productionRunId));
    }

    @PostMapping
    public ApiResponse<RunSetupCostResponse> record(
        @PathVariable("productionRunId") String productionRunId,
        @RequestBody RunSetupRequest request
    ) {
        return ApiResponse.ok(runSetupService.record(productionRunId, request));
    }

    @PostMapping("/{runSetupId}/cancel")
    public ApiResponse<RunSetupCostResponse> cancel(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("runSetupId") String runSetupId,
        @RequestBody(required = false) RunSetupCancelRequest request
    ) {
        return ApiResponse.ok(runSetupService.cancel(productionRunId, runSetupId, request));
    }
}
