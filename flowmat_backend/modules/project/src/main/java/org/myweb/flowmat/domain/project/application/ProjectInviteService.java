package org.myweb.flowmat.domain.project.application;

import java.util.List;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteAcceptRequest;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectInvitePreviewResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectInviteResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;

public interface ProjectInviteService {

    List<ProjectInviteResponse> listInvites(String projectId);

    ProjectInviteResponse createInvite(ProjectInviteRequest request);

    ProjectInvitePreviewResponse previewInvite(String inviteToken);

    ProjectMemberResponse acceptInvite(ProjectInviteAcceptRequest request);

    void cancelInvite(String inviteId);
}
