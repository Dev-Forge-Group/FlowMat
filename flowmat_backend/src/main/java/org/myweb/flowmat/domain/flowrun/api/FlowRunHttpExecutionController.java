package org.myweb.flowmat.domain.flowrun.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpExecutionResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpRequestPreviewResponse;
import org.myweb.flowmat.domain.flowrun.application.FlowRunHttpExecutionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/flow-runs/{flowRunId}/steps/{stepId}")
public class FlowRunHttpExecutionController {
    private final FlowRunHttpExecutionService service;
    @GetMapping("/http-request-preview")
    public ApiResponse<FlowRunHttpRequestPreviewResponse> preview(@PathVariable String flowRunId, @PathVariable String stepId) {
        return ApiResponse.ok(service.preview(flowRunId, stepId));
    }
    @PostMapping("/execute")
    public ApiResponse<FlowRunHttpExecutionResponse> execute(@PathVariable String flowRunId, @PathVariable String stepId,
        HttpServletRequest request) throws IOException {
        return ApiResponse.ok(service.execute(flowRunId, stepId, request.getInputStream()));
    }
    @GetMapping("/http-executions")
    public ApiResponse<List<FlowRunHttpExecutionResponse>> list(@PathVariable String flowRunId, @PathVariable String stepId) {
        return ApiResponse.ok(service.list(flowRunId, stepId));
    }
    @PostMapping("/http-executions/{executionId}/apply")
    public ApiResponse<FlowRunHttpExecutionResponse> apply(@PathVariable String flowRunId, @PathVariable String stepId,
        @PathVariable String executionId) {
        return ApiResponse.ok(service.retryApply(flowRunId, stepId, executionId));
    }
}
