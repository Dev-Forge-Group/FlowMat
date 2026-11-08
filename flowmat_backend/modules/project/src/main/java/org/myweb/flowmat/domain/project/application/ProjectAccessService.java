package org.myweb.flowmat.domain.project.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectAccessService implements ProjectAccess {

    private static final String NOT_DELETED = "N";
    private static final String ACTIVE_MEMBER = "active";
    private static final String ROLE_VIEWER = "viewer";
    private static final String ROLE_EDITOR = "editor";
    private static final String ROLE_OWNER = "owner";

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final PermissionService permissionService;

    @Override
    public String requireCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthUser authUser && authUser.getUserId() != null && !authUser.getUserId().isBlank()) {
            return authUser.getUserId();
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return name;
    }

    public void requireProjectOwnerMatch(String ownerId) {
        String currentUserId = requireCurrentUserId();
        String normalizedOwnerId = ownerId == null ? null : ownerId.trim();
        if (!currentUserId.equals(normalizedOwnerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Project owner must match the authenticated user.");
        }
    }

    public List<Project> listAccessibleProjects() {
        if (permissionService.hasPermission(SystemPermission.PROJECT_VIEW_ALL)) {
            return projectRepository.findAllByDeletedYnOrderByCreatedAtDesc(NOT_DELETED);
        }

        String userId = requireCurrentUserId();
        LinkedHashMap<String, Project> accessible = new LinkedHashMap<>();
        for (Project project : projectRepository.findAllByOwnerIdAndDeletedYnOrderByCreatedAtDesc(userId, NOT_DELETED)) {
            accessible.put(project.getProjectId(), project);
        }

        List<String> memberProjectIds = projectMemberRepository.findAllByUserIdAndMemberStatus(userId, ACTIVE_MEMBER).stream()
            .map(ProjectMember::getProjectId)
            .distinct()
            .toList();
        if (!memberProjectIds.isEmpty()) {
            for (Project project : projectRepository.findAllByProjectIdInAndDeletedYnOrderByCreatedAtDesc(memberProjectIds, NOT_DELETED)) {
                accessible.putIfAbsent(project.getProjectId(), project);
            }
        }

        return accessible.values().stream()
            .sorted(Comparator.comparing(Project::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    @Override
    public void requireProjectReadAccess(String projectId) {
        requireReadableProject(projectId);
    }

    /** The project, after the same check as {@link #requireProjectReadAccess(String)}. */
    public Project requireReadableProject(String projectId) {
        return readableProject(projectId, requireCurrentUserId());
    }

    @Override
    public void requireProjectReadAccess(String projectId, String userId) {
        readableProject(projectId, userId);
    }

    private Project readableProject(String projectId, String userId) {
        Project project = projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ensureProjectReadAccess(project, userId);
        return project;
    }

    @Override
    public void requireProjectWriteAccess(String projectId) {
        requireProjectWriteAccess(projectId, requireCurrentUserId());
    }

    @Override
    public void requireProjectWriteAccess(String projectId, String userId) {
        Project project = projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ensureProjectWriteAccess(project, userId);
    }

    @Override
    public void requireProjectOwnerAccess(String projectId) {
        requireOwnedProject(projectId);
    }

    /** The project, after the same check as {@link #requireProjectOwnerAccess}. */
    public Project requireOwnedProject(String projectId) {
        Project project = projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ensureProjectOwnerAccess(project, requireCurrentUserId());
        return project;
    }

    @Override
    public String resolveCurrentUserRole(String projectId) {
        Project project = projectRepository.findByProjectIdAndDeletedYn(projectId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return resolveProjectRole(project, requireCurrentUserId());
    }

    private void ensureProjectReadAccess(Project project, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (userId.equals(project.getOwnerId())) {
            return;
        }
        boolean isActiveMember = projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus(
            project.getProjectId(),
            userId,
            ACTIVE_MEMBER
        );
        if (!isActiveMember) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Project membership is required.");
        }
    }

    private void ensureProjectWriteAccess(Project project, String userId) {
        String role = resolveProjectRole(project, userId);
        if (!ROLE_OWNER.equals(role) && !ROLE_EDITOR.equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Project write access is required.");
        }
    }

    private void ensureProjectOwnerAccess(Project project, String userId) {
        String role = resolveProjectRole(project, userId);
        if (!ROLE_OWNER.equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Project owner access is required.");
        }
    }

    private String resolveProjectRole(Project project, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (userId.equals(project.getOwnerId())) {
            return ROLE_OWNER;
        }
        return projectMemberRepository.findByProjectIdAndUserIdAndMemberStatus(
                project.getProjectId(),
                userId,
                ACTIVE_MEMBER
            )
            .map(ProjectMember::getProjectRole)
            .map(String::trim)
            .map(String::toLowerCase)
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Project membership is required."));
    }
}
