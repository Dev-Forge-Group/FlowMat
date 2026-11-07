package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.MaterialRequirementResponse;
import org.myweb.flowmat.domain.production.application.MaterialRequirementService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What open work orders still need against usable stock (docs/domain/material-requirements.md). Read only. */
@RestController
@RequiredArgsConstructor
public class MaterialRequirementController {

    private final MaterialRequirementService materialRequirementService;

    @GetMapping("/material-requirements")
    public ApiResponse<MaterialRequirementResponse> summary(@RequestParam("projectId") String projectId) {
        return ApiResponse.ok(materialRequirementService.summary(projectId));
    }
}
