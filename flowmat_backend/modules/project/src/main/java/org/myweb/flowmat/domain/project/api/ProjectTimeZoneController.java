package org.myweb.flowmat.domain.project.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectTimeZoneService;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectTimeZoneResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@RequestMapping("/projects/{projectId}/time-zone")
public class ProjectTimeZoneController {
    private final ProjectTimeZoneService zones;
    @GetMapping public ApiResponse<ProjectTimeZoneResponse> get(@PathVariable String projectId) { return ApiResponse.ok(zones.get(projectId)); }
    @PutMapping public ApiResponse<ProjectTimeZoneResponse> set(@PathVariable String projectId,@RequestBody JsonNode body) { return ApiResponse.ok(zones.set(projectId,body)); }
}
