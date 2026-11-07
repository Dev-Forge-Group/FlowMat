package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.BomScrapPerformanceResponse;
import org.myweb.flowmat.domain.production.application.BomScrapPerformanceService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Planned scrap against what a BOM revision's finished runs lost (docs/architecture/adr/ADR-034-bom-scrap-performance.md). Read only. */
@RestController
@RequiredArgsConstructor
public class BomScrapPerformanceController {

    private final BomScrapPerformanceService bomScrapPerformanceService;

    @GetMapping("/boms/{bomId}/scrap-performance")
    public ApiResponse<BomScrapPerformanceResponse> performance(@PathVariable("bomId") String bomId) {
        return ApiResponse.ok(bomScrapPerformanceService.performance(bomId));
    }
}
