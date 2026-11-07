package org.myweb.flowmat.domain.quality.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectResolveRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.DefectResponse;
import org.myweb.flowmat.domain.quality.application.QualityService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Defects (docs/domain/quality-inspection.md). Logging one moves no stock. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/defects")
public class DefectController {

    private final QualityService qualityService;

    @GetMapping
    public ApiResponse<List<DefectResponse>> listDefects(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "productionRunId", required = false) String productionRunId,
        @RequestParam(value = "lotId", required = false) String lotId,
        @RequestParam(value = "itemId", required = false) String itemId,
        @RequestParam(value = "openOnly", defaultValue = "false") boolean openOnly
    ) {
        return ApiResponse.ok(qualityService.listDefects(projectId, productionRunId, lotId, itemId, openOnly));
    }

    @PostMapping
    public ApiResponse<DefectResponse> logDefect(@Valid @RequestBody DefectCreateRequest request) {
        return ApiResponse.ok(qualityService.logDefect(request));
    }

    @PostMapping("/{defectLogId}/resolve")
    public ApiResponse<DefectResponse> resolveDefect(
        @PathVariable("defectLogId") String defectLogId,
        @Valid @RequestBody DefectResolveRequest request
    ) {
        return ApiResponse.ok(qualityService.resolveDefect(defectLogId, request));
    }
}
