package org.myweb.flowmat.domain.production.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.BuildableQuantityResponse;
import org.myweb.flowmat.domain.production.application.BuildableQuantityService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** How much of a BOM's product the usable stock could make now (docs/domain/material-requirements.md). Read only. */
@RestController
@RequiredArgsConstructor
public class BuildableQuantityController {

    private final BuildableQuantityService buildableQuantityService;

    @GetMapping("/boms/{bomId}/buildable")
    public ApiResponse<BuildableQuantityResponse> buildable(@PathVariable("bomId") String bomId) {
        return ApiResponse.ok(buildableQuantityService.buildable(bomId));
    }

    /** Every approved BOM of the project. */
    @GetMapping("/boms/buildable")
    public ApiResponse<List<BuildableQuantityResponse>> approved(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(buildableQuantityService.approved(projectId));
    }
}
