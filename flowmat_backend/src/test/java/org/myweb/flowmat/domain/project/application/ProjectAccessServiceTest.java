package org.myweb.flowmat.domain.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ProcessRepository processRepository;

    @Mock
    private ProcessIoRepository processIoRepository;

    @Mock
    private ProcessConnectionRepository processConnectionRepository;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private ProjectAccessService projectAccessService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void projectOwnerCanAccessProject() {
        setAuthenticatedUser("owner-1");
        Project project = project("project-1", "owner-1");
        when(projectRepository.findByProjectIdAndDeletedYn("project-1", "N")).thenReturn(Optional.of(project));

        Project result = projectAccessService.requireReadableProject("project-1");

        assertThat(result.getProjectId()).isEqualTo("project-1");
    }

    @Test
    void activeMemberCanAccessProject() {
        setAuthenticatedUser("member-1");
        Project project = project("project-2", "owner-2");
        when(projectRepository.findByProjectIdAndDeletedYn("project-2", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus("project-2", "member-1", "active"))
            .thenReturn(true);

        Project result = projectAccessService.requireReadableProject("project-2");

        assertThat(result.getProjectId()).isEqualTo("project-2");
    }

    @Test
    void nonMemberIsRejected() {
        setAuthenticatedUser("outsider-1");
        Project project = project("project-3", "owner-3");
        when(projectRepository.findByProjectIdAndDeletedYn("project-3", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserIdAndMemberStatus("project-3", "outsider-1", "active"))
            .thenReturn(false);

        assertThatThrownBy(() -> projectAccessService.requireProjectReadAccess("project-3"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void viewerCannotWriteProjectResources() {
        setAuthenticatedUser("viewer-1");
        Project project = project("project-4", "owner-4");
        ProjectMember member = new ProjectMember();
        member.setProjectId("project-4");
        member.setUserId("viewer-1");
        member.setMemberStatus("active");
        member.setProjectRole("viewer");
        when(projectRepository.findByProjectIdAndDeletedYn("project-4", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.findByProjectIdAndUserIdAndMemberStatus("project-4", "viewer-1", "active"))
            .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> projectAccessService.requireProjectWriteAccess("project-4"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void editorCanWriteProjectResources() {
        setAuthenticatedUser("editor-1");
        Project project = project("project-5", "owner-5");
        ProjectMember member = new ProjectMember();
        member.setProjectId("project-5");
        member.setUserId("editor-1");
        member.setMemberStatus("active");
        member.setProjectRole("editor");
        when(projectRepository.findByProjectIdAndDeletedYn("project-5", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.findByProjectIdAndUserIdAndMemberStatus("project-5", "editor-1", "active"))
            .thenReturn(Optional.of(member));

        assertThatCode(() -> projectAccessService.requireProjectWriteAccess("project-5")).doesNotThrowAnyException();
    }

    @Test
    void editorCannotUseOwnerOnlyPath() {
        setAuthenticatedUser("editor-2");
        Project project = project("project-6", "owner-6");
        ProjectMember member = new ProjectMember();
        member.setProjectId("project-6");
        member.setUserId("editor-2");
        member.setMemberStatus("active");
        member.setProjectRole("editor");
        when(projectRepository.findByProjectIdAndDeletedYn("project-6", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.findByProjectIdAndUserIdAndMemberStatus("project-6", "editor-2", "active"))
            .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> projectAccessService.requireProjectOwnerAccess("project-6"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void listAccessibleProjectsMergesOwnerAndMembershipProjects() {
        setAuthenticatedUser("user-1");
        Project owned = project("owned-project", "user-1");
        Project shared = project("shared-project", "owner-2");
        ProjectMember member = new ProjectMember();
        member.setProjectId("shared-project");
        member.setUserId("user-1");

        when(projectRepository.findAllByOwnerIdAndDeletedYnOrderByCreatedAtDesc("user-1", "N"))
            .thenReturn(List.of(owned));
        when(projectMemberRepository.findAllByUserIdAndMemberStatus("user-1", "active"))
            .thenReturn(List.of(member));
        when(projectRepository.findAllByProjectIdInAndDeletedYnOrderByCreatedAtDesc(List.of("shared-project"), "N"))
            .thenReturn(List.of(shared));

        List<Project> projects = projectAccessService.listAccessibleProjects();

        assertThat(projects).extracting(Project::getProjectId)
            .containsExactlyInAnyOrder("owned-project", "shared-project");
    }

    private static void setAuthenticatedUser(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(new AuthUser(userId), null)
        );
    }

    private static Project project(String projectId, String ownerId) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setOwnerId(ownerId);
        project.setDeletedYn("N");
        return project;
    }
}
