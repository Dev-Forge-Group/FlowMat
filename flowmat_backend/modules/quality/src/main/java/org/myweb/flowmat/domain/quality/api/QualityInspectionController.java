package org.myweb.flowmat.domain.quality.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.QualityInspectionResponse;
import org.myweb.flowmat.domain.quality.application.QualityService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inspections (docs/domain/quality-inspection.md). They are never edited or deleted; a new one follows a wrong one. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quality-inspections")
public class QualityInspectionController {

    private final QualityService qualityService;

    @GetMapping
    public ApiResponse<List<QualityInspectionResponse>> listInspections(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "productionRunId", required = false) String productionRunId,
        @RequestParam(value = "lotId", required = false) String lotId,
        @RequestParam(value = "itemId", required = false) String itemId
    ) {
        return ApiResponse.ok(qualityService.listInspections(projectId, productionRunId, lotId, itemId));
    }

    @PostMapping
    public ApiResponse<QualityInspectionResponse> recordInspection(@Valid @RequestBody QualityInspectionCreateRequest request) {
        return ApiResponse.ok(qualityService.recordInspection(request));
    }
}
