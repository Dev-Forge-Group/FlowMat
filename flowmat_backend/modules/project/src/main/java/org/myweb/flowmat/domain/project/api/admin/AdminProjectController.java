package org.myweb.flowmat.domain.project.api.admin;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectService;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectSummaryResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/projects")
public class AdminProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResponse<List<ProjectSummaryResponse>> listAllProjects() {
        return ApiResponse.ok(projectService.listProjects());
    }
}
