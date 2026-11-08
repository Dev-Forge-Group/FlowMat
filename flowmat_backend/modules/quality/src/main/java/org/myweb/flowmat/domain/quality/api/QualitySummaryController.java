package org.myweb.flowmat.domain.quality.api;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.response.QualitySummaryResponse;
import org.myweb.flowmat.domain.quality.application.QualitySummaryService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The project's quality at a glance (docs/domain/quality-inspection.md "요약"). */
@RestController
@RequiredArgsConstructor
public class QualitySummaryController {

    private final QualitySummaryService qualitySummaryService;

    @GetMapping("/quality/summary")
    public ApiResponse<QualitySummaryResponse> summary(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return ApiResponse.ok(qualitySummaryService.summary(projectId, from, to));
    }
}
