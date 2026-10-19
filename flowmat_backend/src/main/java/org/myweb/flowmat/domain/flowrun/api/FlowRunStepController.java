package org.myweb.flowmat.domain.flowrun.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCreateRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepScheduleRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunEventResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunRoutePreviewResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepAttemptResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepLineageResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepResponse;
import org.myweb.flowmat.domain.flowrun.application.FlowRunStepService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/flow-runs/{flowRunId}")
public class FlowRunStepController {
    private final FlowRunStepService service;

    @PostMapping("/steps")
    public ApiResponse<FlowRunStepResponse> create(@PathVariable String flowRunId,
        @Valid @RequestBody FlowRunStepCreateRequest request) {
        return ApiResponse.ok(service.create(flowRunId, request));
    }

    @GetMapping("/steps")
    public ApiResponse<List<FlowRunStepResponse>> list(@PathVariable String flowRunId) {
        return ApiResponse.ok(service.list(flowRunId));
    }

    @GetMapping("/steps/{stepId}/lineage")
    public ApiResponse<FlowRunStepLineageResponse> lineage(@PathVariable String flowRunId,
        @PathVariable String stepId) {
        return ApiResponse.ok(service.lineage(flowRunId, stepId));
    }

    @PostMapping("/steps/{stepId}/start")
    public ApiResponse<FlowRunStepResponse> start(@PathVariable String flowRunId, @PathVariable String stepId) {
        return ApiResponse.ok(service.start(flowRunId, stepId));
    }

    @PutMapping("/steps/{stepId}/schedule")
    public ApiResponse<FlowRunStepResponse> schedule(@PathVariable String flowRunId, @PathVariable String stepId,
        @Valid @RequestBody FlowRunStepScheduleRequest request) {
        return ApiResponse.ok(service.schedule(flowRunId, stepId, request));
    }

    @PostMapping("/steps/{stepId}/retry")
    public ApiResponse<FlowRunStepResponse> retry(@PathVariable String flowRunId, @PathVariable String stepId) {
        return ApiResponse.ok(service.retry(flowRunId, stepId));
    }

    @PostMapping("/steps/{stepId}/complete")
    public ApiResponse<FlowRunStepResponse> complete(@PathVariable String flowRunId, @PathVariable String stepId,
        @Valid @RequestBody FlowRunStepCompleteRequest request) {
        return ApiResponse.ok(service.complete(flowRunId, stepId, request));
    }

    @PostMapping("/steps/{stepId}/preview")
    public ApiResponse<List<FlowRunRoutePreviewResponse>> preview(@PathVariable String flowRunId,
        @PathVariable String stepId, @Valid @RequestBody FlowRunStepCompleteRequest request) {
        return ApiResponse.ok(service.preview(flowRunId, stepId, request));
    }

    @PostMapping("/steps/{stepId}/fail")
    public ApiResponse<FlowRunStepResponse> fail(@PathVariable String flowRunId, @PathVariable String stepId,
        @Valid @RequestBody FlowRunStepFailRequest request) {
        return ApiResponse.ok(service.fail(flowRunId, stepId, request));
    }

    @GetMapping("/steps/{stepId}/attempts")
    public ApiResponse<List<FlowRunStepAttemptResponse>> attempts(@PathVariable String flowRunId,
        @PathVariable String stepId) {
        return ApiResponse.ok(service.attempts(flowRunId, stepId));
    }

    @GetMapping("/events")
    public ApiResponse<List<FlowRunEventResponse>> events(@PathVariable String flowRunId) {
        return ApiResponse.ok(service.events(flowRunId));
    }

    /** A body that is not JSON, or a snapshot number past the safe limits, is the caller's mistake (500 before). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "inputSnapshot and outputSnapshot must be valid JSON with numbers within the safe conversion limits."));
    }
}
