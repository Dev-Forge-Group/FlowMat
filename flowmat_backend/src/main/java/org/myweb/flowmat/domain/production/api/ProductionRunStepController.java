package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStepCompleteRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStepFailRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunStepResponse;
import org.myweb.flowmat.domain.production.application.ProductionRunStepService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A production run's process steps (docs/architecture/adr/ADR-011-production-steps.md decision 2). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs/{productionRunId}/steps")
public class ProductionRunStepController {

    private final ProductionRunStepService steps;

    @GetMapping
    public ApiResponse<List<ProductionRunStepResponse>> list(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(steps.list(productionRunId));
    }

    @PostMapping("/{stepId}/start")
    public ApiResponse<ProductionRunStepResponse> start(@PathVariable("productionRunId") String productionRunId,
                                                        @PathVariable("stepId") String stepId) {
        return ApiResponse.ok(steps.start(productionRunId, stepId));
    }

    @PostMapping("/{stepId}/complete")
    public ApiResponse<ProductionRunStepResponse> complete(@PathVariable("productionRunId") String productionRunId,
                                                           @PathVariable("stepId") String stepId,
                                                           @RequestBody(required = false) ProductionRunStepCompleteRequest request) {
        return ApiResponse.ok(steps.complete(productionRunId, stepId, request));
    }

    @PostMapping("/{stepId}/fail")
    public ApiResponse<ProductionRunStepResponse> fail(@PathVariable("productionRunId") String productionRunId,
                                                       @PathVariable("stepId") String stepId,
                                                       @Valid @RequestBody ProductionRunStepFailRequest request) {
        return ApiResponse.ok(steps.fail(productionRunId, stepId, request));
    }
}
