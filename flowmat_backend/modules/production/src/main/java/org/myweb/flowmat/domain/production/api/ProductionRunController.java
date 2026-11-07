package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunFinishRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStartRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunItemCancelRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunItemResponse;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunResponse;
import org.myweb.flowmat.domain.production.application.ProductionRunService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs")
public class ProductionRunController {

    private final ProductionRunService productionRunService;

    @GetMapping
    public ApiResponse<List<ProductionRunResponse>> listRuns(@RequestParam("workflowId") String workflowId) {
        return ApiResponse.ok(productionRunService.listRuns(workflowId));
    }

    @PostMapping("/start")
    public ApiResponse<ProductionRunResponse> startRun(@Valid @RequestBody ProductionRunStartRequest request) {
        return ApiResponse.ok(productionRunService.startRun(request));
    }

    @GetMapping("/{productionRunId}")
    public ApiResponse<ProductionRunResponse> getRun(
        @PathVariable("productionRunId") String productionRunId
    ) {
        return ApiResponse.ok(productionRunService.getRun(productionRunId));
    }

    @GetMapping("/{productionRunId}/items")
    public ApiResponse<List<ProductionRunItemResponse>> listRunItems(
        @PathVariable("productionRunId") String productionRunId
    ) {
        return ApiResponse.ok(productionRunService.listRunItems(productionRunId));
    }

    @PostMapping("/{productionRunId}/items")
    public ApiResponse<ProductionRunItemResponse> recordRunItem(
        @PathVariable("productionRunId") String productionRunId,
        @Valid @RequestBody ProductionRunItemRecordRequest request
    ) {
        return ApiResponse.ok(productionRunService.recordRunItem(productionRunId, request));
    }

    @PostMapping("/{productionRunId}/finish")
    public ApiResponse<ProductionRunResponse> finishRun(
        @PathVariable("productionRunId") String productionRunId,
        @RequestBody ProductionRunFinishRequest request
    ) {
        return ApiResponse.ok(productionRunService.finishRun(productionRunId, request));
    }

    @PostMapping("/{productionRunId}/items/{productionRunItemId}/cancel")
    public ApiResponse<ProductionRunItemResponse> cancelRunItem(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("productionRunItemId") String productionRunItemId,
        @Valid @RequestBody RunItemCancelRequest request
    ) {
        return ApiResponse.ok(productionRunService.cancelRunItem(productionRunId, productionRunItemId, request.reason(),
            Boolean.TRUE.equals(request.reallocate())));
    }
}
