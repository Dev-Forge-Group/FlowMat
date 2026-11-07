package org.myweb.flowmat.domain.project.application;

import java.util.List;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectMemberRoleUpdateRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;

public interface ProjectMemberService {

    List<ProjectMemberResponse> listMembers(String projectId);

    ProjectMemberResponse updateMemberRole(String projectMemberId, ProjectMemberRoleUpdateRequest request);

    void removeMember(String projectMemberId);
}
