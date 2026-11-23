package org.myweb.flowmat.domain.production.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.ResourceCapacityRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ResourceCapacityResponse;
import org.myweb.flowmat.domain.production.application.ResourceCapacityService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A project's energy and water limits (docs/architecture/adr/ADR-046-resources-first-class.md decision 5). */
@RestController
@RequiredArgsConstructor
public class ResourceCapacityController {

    private final ResourceCapacityService service;

    @GetMapping("/projects/{projectId}/resource-capacities")
    public ApiResponse<List<ResourceCapacityResponse>> list(@PathVariable("projectId") String projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    /** Replaces the whole list; the project owner only. */
    @PutMapping("/projects/{projectId}/resource-capacities")
    public ApiResponse<List<ResourceCapacityResponse>> replace(@PathVariable("projectId") String projectId,
        @Valid @RequestBody ResourceCapacityRequest request) {
        return ApiResponse.ok(service.replace(projectId, request));
    }
}
