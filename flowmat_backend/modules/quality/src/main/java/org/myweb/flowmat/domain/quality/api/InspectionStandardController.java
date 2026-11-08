package org.myweb.flowmat.domain.quality.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.request.InspectionStandardRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.InspectionStandardResponse;
import org.myweb.flowmat.domain.quality.api.dto.response.RunQualityChecklistResponse;
import org.myweb.flowmat.domain.quality.application.InspectionStandardService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inspection standards and a run's quality checklist (docs/domain/inspection-standard.md). */
@RestController
@RequiredArgsConstructor
public class InspectionStandardController {

    private final InspectionStandardService inspectionStandardService;

    @GetMapping("/inspection-standards")
    public ApiResponse<List<InspectionStandardResponse>> list(
        @RequestParam String projectId,
        @RequestParam(required = false) String itemId
    ) {
        return ApiResponse.ok(inspectionStandardService.list(projectId, itemId));
    }

    @PostMapping("/inspection-standards")
    public ApiResponse<InspectionStandardResponse> create(@Valid @RequestBody InspectionStandardRequest request) {
        return ApiResponse.ok(inspectionStandardService.create(request));
    }

    @PutMapping("/inspection-standards/{standardId}")
    public ApiResponse<InspectionStandardResponse> update(
        @PathVariable String standardId,
        @Valid @RequestBody InspectionStandardRequest request
    ) {
        return ApiResponse.ok(inspectionStandardService.update(standardId, request));
    }

    @DeleteMapping("/inspection-standards/{standardId}")
    public ApiResponse<Void> delete(@PathVariable String standardId) {
        inspectionStandardService.delete(standardId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/production-runs/{productionRunId}/quality-checklist")
    public ApiResponse<RunQualityChecklistResponse> checklist(@PathVariable String productionRunId) {
        return ApiResponse.ok(inspectionStandardService.checklist(productionRunId));
    }
}
