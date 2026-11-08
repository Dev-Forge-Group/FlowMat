package org.myweb.flowmat.domain.project.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectMemberRoleUpdateRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectMemberServiceImpl implements ProjectMemberService {

    private static final String ACTIVE_MEMBER = "active";
    private static final String REMOVED_MEMBER = "removed";

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    public List<ProjectMemberResponse> listMembers(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        return projectMemberRepository.findAllByProjectIdAndMemberStatusOrderByCreatedAtAsc(projectId, ACTIVE_MEMBER).stream()
            .map(ProjectMemberServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ProjectMemberResponse updateMemberRole(String projectMemberId, ProjectMemberRoleUpdateRequest request) {
        ProjectMember member = findActiveMember(projectMemberId);
        projectAccessService.requireProjectOwnerAccess(member.getProjectId());
        if ("owner".equalsIgnoreCase(member.getProjectRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The owner role cannot be changed here.");
        }
        member.setProjectRole(ProjectInviteServiceImpl.normalizeAssignableRole(request.projectRole()));
        return toResponse(projectMemberRepository.save(member));
    }

    @Override
    @Transactional
    public void removeMember(String projectMemberId) {
        ProjectMember member = findActiveMember(projectMemberId);
        projectAccessService.requireProjectOwnerAccess(member.getProjectId());
        if ("owner".equalsIgnoreCase(member.getProjectRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The owner cannot be removed.");
        }
        member.setMemberStatus(REMOVED_MEMBER);
        projectMemberRepository.save(member);
    }

    private ProjectMember findActiveMember(String projectMemberId) {
        return projectMemberRepository.findByProjectMemberIdAndMemberStatus(projectMemberId, ACTIVE_MEMBER)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static ProjectMemberResponse toResponse(ProjectMember member) {
        return new ProjectMemberResponse(
            member.getProjectMemberId(),
            member.getProjectId(),
            member.getUserId(),
            member.getProjectRole(),
            member.getMemberStatus(),
            member.getJoinedAt()
        );
    }
}
