package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.RunCorrectionCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunCorrectionRejectRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunCorrectionResponse;
import org.myweb.flowmat.domain.production.application.ProductionRunCorrectionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Corrections of finished runs (docs/domain/production-run-correction.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-runs/{productionRunId}/corrections")
public class ProductionRunCorrectionController {

    private final ProductionRunCorrectionService correctionService;

    @GetMapping
    public ApiResponse<List<RunCorrectionResponse>> listCorrections(@PathVariable("productionRunId") String productionRunId) {
        return ApiResponse.ok(correctionService.listCorrections(productionRunId));
    }

    @PostMapping
    public ApiResponse<RunCorrectionResponse> requestCorrection(
        @PathVariable("productionRunId") String productionRunId,
        @Valid @RequestBody RunCorrectionCreateRequest request
    ) {
        return ApiResponse.ok(correctionService.requestCorrection(productionRunId, request));
    }

    @PostMapping("/{correctionId}/approve")
    public ApiResponse<RunCorrectionResponse> approveCorrection(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("correctionId") String correctionId
    ) {
        return ApiResponse.ok(correctionService.approveCorrection(productionRunId, correctionId));
    }

    @PostMapping("/{correctionId}/reject")
    public ApiResponse<RunCorrectionResponse> rejectCorrection(
        @PathVariable("productionRunId") String productionRunId,
        @PathVariable("correctionId") String correctionId,
        @Valid @RequestBody RunCorrectionRejectRequest request
    ) {
        return ApiResponse.ok(correctionService.rejectCorrection(productionRunId, correctionId, request.note()));
    }

    /** A body that is not JSON, or minutes and quantities that are not numbers, are the caller's mistake, not the server's. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "Send the correction as JSON, with quantities as numbers and minutes as whole numbers."));
    }
}
