package org.myweb.flowmat.domain.project.api;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectCreateRequest;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectUpdateRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectSummaryResponse;
import org.myweb.flowmat.domain.project.application.ProjectService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResponse<List<ProjectSummaryResponse>> listProjects() {
        return ApiResponse.ok(projectService.listProjects());
    }

    @PostMapping
    public ApiResponse<ProjectResponse> createProject(@Valid @RequestBody ProjectCreateRequest request) {
        return ApiResponse.ok(projectService.createProject(request));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> getProject(@PathVariable("projectId") String projectId) {
        return ApiResponse.ok(projectService.getProject(projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectResponse> updateProject(
        @PathVariable("projectId") String projectId,
        @RequestBody ProjectUpdateRequest request
    ) {
        return ApiResponse.ok(projectService.updateProject(projectId, request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable("projectId") String projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.ok(null);
    }
}
