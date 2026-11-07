package org.myweb.flowmat.domain.flowrun.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFinishRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunCancelRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStartRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunResponse;
import org.myweb.flowmat.domain.flowrun.application.FlowRunService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/flow-runs")
public class FlowRunController {

    private final FlowRunService service;

    @PostMapping
    public ApiResponse<FlowRunResponse> start(@Valid @RequestBody FlowRunStartRequest request) {
        return ApiResponse.ok(service.start(request));
    }

    @PostMapping("/graph")
    public ApiResponse<FlowRunResponse> startGraph(@Valid @RequestBody FlowRunStartRequest request) {
        return ApiResponse.ok(service.startGraph(request));
    }

    @GetMapping
    public ApiResponse<List<FlowRunResponse>> list(@RequestParam String workflowId) {
        return ApiResponse.ok(service.list(workflowId));
    }

    @GetMapping("/{flowRunId}")
    public ApiResponse<FlowRunResponse> get(@PathVariable String flowRunId) {
        return ApiResponse.ok(service.get(flowRunId));
    }

    @PostMapping("/{flowRunId}/finish")
    public ApiResponse<FlowRunResponse> finish(
        @PathVariable String flowRunId, @Valid @RequestBody FlowRunFinishRequest request
    ) {
        return ApiResponse.ok(service.finish(flowRunId, request));
    }

    @PostMapping("/{flowRunId}/cancel")
    public ApiResponse<FlowRunResponse> cancel(
        @PathVariable String flowRunId, @Valid @RequestBody FlowRunCancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(flowRunId, request));
    }

    @PostMapping("/{flowRunId}/fail")
    public ApiResponse<FlowRunResponse> fail(
        @PathVariable String flowRunId, @Valid @RequestBody FlowRunFailRequest request
    ) {
        return ApiResponse.ok(service.fail(flowRunId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "Flow run request must be valid JSON with inputPayload numbers within the safe conversion limits."));
    }
}
