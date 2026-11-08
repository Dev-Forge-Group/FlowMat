package org.myweb.flowmat.domain.project.application;

import java.util.List;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectCreateRequest;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectUpdateRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectSummaryResponse;

public interface ProjectService {

    List<ProjectSummaryResponse> listProjects();

    ProjectResponse createProject(ProjectCreateRequest request);

    ProjectResponse getProject(String projectId);

    ProjectResponse updateProject(String projectId, ProjectUpdateRequest request);

    void deleteProject(String projectId);
}
