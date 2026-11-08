package org.myweb.flowmat.domain.quality.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.request.CorrectiveActionRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityCloseRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityDefectsRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityUpdateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityVerificationDueRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.NonconformityVerifyRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityNoteRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.NonconformityDefectLinkResponse;
import org.myweb.flowmat.domain.quality.api.dto.response.NonconformityResponse;
import org.myweb.flowmat.domain.quality.application.NonconformityDefectLinkService;
import org.myweb.flowmat.domain.quality.application.NonconformityService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Nonconformities and corrective actions (docs/domain/nonconformity.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/nonconformities")
public class NonconformityController {

    private final NonconformityService nonconformityService;
    private final NonconformityDefectLinkService nonconformityDefectLinkService;

    @GetMapping
    public ApiResponse<List<NonconformityResponse>> list(
        @RequestParam String projectId,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(nonconformityService.list(projectId, status));
    }

    /** Which open or closed nonconformity holds each of the project's defects. */
    @GetMapping("/defect-links")
    public ApiResponse<List<NonconformityDefectLinkResponse>> defectLinks(@RequestParam String projectId) {
        return ApiResponse.ok(nonconformityDefectLinkService.links(projectId));
    }

    @GetMapping("/{nonconformityId}")
    public ApiResponse<NonconformityResponse> get(@PathVariable String nonconformityId) {
        return ApiResponse.ok(nonconformityService.get(nonconformityId));
    }

    @PostMapping
    public ApiResponse<NonconformityResponse> create(@Valid @RequestBody NonconformityCreateRequest request) {
        return ApiResponse.ok(nonconformityService.create(request));
    }

    @PutMapping("/{nonconformityId}")
    public ApiResponse<NonconformityResponse> update(
        @PathVariable String nonconformityId,
        @Valid @RequestBody NonconformityUpdateRequest request
    ) {
        return ApiResponse.ok(nonconformityService.update(nonconformityId, request));
    }

    @PostMapping("/{nonconformityId}/defects")
    public ApiResponse<NonconformityResponse> addDefects(
        @PathVariable String nonconformityId,
        @Valid @RequestBody NonconformityDefectsRequest request
    ) {
        return ApiResponse.ok(nonconformityService.addDefects(nonconformityId, request.defectLogIds()));
    }

    @PostMapping("/{nonconformityId}/actions")
    public ApiResponse<NonconformityResponse> addAction(
        @PathVariable String nonconformityId,
        @Valid @RequestBody CorrectiveActionRequest request
    ) {
        return ApiResponse.ok(nonconformityService.addAction(nonconformityId, request));
    }

    @PostMapping("/{nonconformityId}/actions/{correctiveActionId}/complete")
    public ApiResponse<NonconformityResponse> completeAction(
        @PathVariable String nonconformityId,
        @PathVariable String correctiveActionId,
        @Valid @RequestBody QualityNoteRequest request
    ) {
        return ApiResponse.ok(nonconformityService.finishAction(nonconformityId, correctiveActionId, true, request.note()));
    }

    @PostMapping("/{nonconformityId}/actions/{correctiveActionId}/cancel")
    public ApiResponse<NonconformityResponse> cancelAction(
        @PathVariable String nonconformityId,
        @PathVariable String correctiveActionId,
        @Valid @RequestBody QualityNoteRequest request
    ) {
        return ApiResponse.ok(nonconformityService.finishAction(nonconformityId, correctiveActionId, false, request.note()));
    }

    @PostMapping("/{nonconformityId}/close")
    public ApiResponse<NonconformityResponse> close(
        @PathVariable String nonconformityId,
        @Valid @RequestBody NonconformityCloseRequest request
    ) {
        return ApiResponse.ok(nonconformityService.close(nonconformityId, request.note(), request.verificationDueDate()));
    }

    /** Moves or clears the day a closed nonconformity's actions are due to be checked (docs/domain/nonconformity.md N13). */
    @PutMapping("/{nonconformityId}/verification-due")
    public ApiResponse<NonconformityResponse> setVerificationDue(
        @PathVariable String nonconformityId,
        @RequestBody NonconformityVerificationDueRequest request
    ) {
        return ApiResponse.ok(nonconformityService.setVerificationDue(nonconformityId, request.verificationDueDate()));
    }

    /** Whether a closed nonconformity's actions worked (docs/domain/nonconformity.md N12). */
    @PostMapping("/{nonconformityId}/verify")
    public ApiResponse<NonconformityResponse> verify(
        @PathVariable String nonconformityId,
        @Valid @RequestBody NonconformityVerifyRequest request
    ) {
        return ApiResponse.ok(nonconformityService.verify(nonconformityId, request.result(), request.note()));
    }

    @PostMapping("/{nonconformityId}/cancel")
    public ApiResponse<NonconformityResponse> cancel(
        @PathVariable String nonconformityId,
        @Valid @RequestBody QualityNoteRequest request
    ) {
        return ApiResponse.ok(nonconformityService.cancel(nonconformityId, request.note()));
    }

    /** A body that is not JSON, or a day that is not YYYY-MM-DD, is the caller's mistake rather than a server error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("The request body is not valid JSON, or a date is not YYYY-MM-DD."));
    }
}
