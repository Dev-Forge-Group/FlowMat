package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunInstructionCheckRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunInstructionResponse;
import org.myweb.flowmat.domain.production.application.RunInstructionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A production run's work instruction checklist (docs/domain/work-instruction.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs/{productionRunId}/instruction")
public class RunInstructionController {

    private final RunInstructionService runInstructionService;

    @GetMapping
    public ApiResponse<RunInstructionResponse> view(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(runInstructionService.view(productionRunId));
    }

    @PostMapping("/steps/{stepId}/check")
    public ApiResponse<RunInstructionResponse> check(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("stepId") String stepId,
        @RequestBody(required = false) RunInstructionCheckRequest request
    ) {
        return ApiResponse.ok(runInstructionService.check(productionRunId, stepId, request));
    }

    @DeleteMapping("/steps/{stepId}/check")
    public ApiResponse<RunInstructionResponse> uncheck(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("stepId") String stepId
    ) {
        return ApiResponse.ok(runInstructionService.uncheck(productionRunId, stepId));
    }
}
