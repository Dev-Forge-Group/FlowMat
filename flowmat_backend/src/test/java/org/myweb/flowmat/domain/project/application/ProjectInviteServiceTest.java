package org.myweb.flowmat.domain.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteAcceptRequest;
import org.myweb.flowmat.domain.project.api.dto.request.ProjectInviteRequest;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectInviteResponse;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectMemberResponse;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.domain.project.domain.entity.ProjectInvite;
import org.myweb.flowmat.domain.project.domain.entity.ProjectMember;
import org.myweb.flowmat.domain.project.repository.ProjectInviteRepository;
import org.myweb.flowmat.domain.project.repository.ProjectMemberRepository;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.domain.user.application.publicapi.UserQuery;
import org.myweb.flowmat.domain.user.application.publicapi.UserView;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.mail.MailService;
import org.myweb.flowmat.global.slack.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
class ProjectInviteServiceTest {

    @Mock
    private ProjectInviteRepository projectInviteRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private UserQuery userQuery;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MailService mailService;

    @Mock
    private SlackNotificationService slackNotificationService;

    @InjectMocks
    private ProjectInviteServiceImpl projectInviteService;

    @Test
    void createInviteReturnsPendingInvite() {
        Project project = project("project-1", "owner-1");
        UserView owner = user("owner-1", "owner@flowmat.local");
        when(projectAccessService.requireProjectOwnerAccess("project-1")).thenReturn(project);
        when(projectAccessService.requireCurrentUserId()).thenReturn("owner-1");
        when(userQuery.findById("owner-1")).thenReturn(Optional.of(owner));
        when(userQuery.findByEmail("guest@flowmat.local")).thenReturn(Optional.empty());
        when(projectInviteRepository.existsByProjectIdAndInvitedEmailIgnoreCaseAndInviteStatus("project-1", "guest@flowmat.local", "pending"))
            .thenReturn(false);
        when(idGenerator.generate()).thenReturn("invite-1");
        when(projectInviteRepository.save(any(ProjectInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectInviteResponse response = projectInviteService.createInvite(
            new ProjectInviteRequest("project-1", "guest@flowmat.local", "editor")
        );

        assertThat(response.inviteId()).isEqualTo("invite-1");
        assertThat(response.projectId()).isEqualTo("project-1");
        assertThat(response.projectRole()).isEqualTo("editor");
        assertThat(response.inviteStatus()).isEqualTo("pending");
        assertThat(response.inviteToken()).isNotBlank();
    }

    @Test
    void createInviteRejectsDuplicatePendingInvite() {
        Project project = project("project-2", "owner-2");
        when(projectAccessService.requireProjectOwnerAccess("project-2")).thenReturn(project);
        when(projectInviteRepository.existsByProjectIdAndInvitedEmailIgnoreCaseAndInviteStatus("project-2", "guest@flowmat.local", "pending"))
            .thenReturn(true);

        assertThatThrownBy(() -> projectInviteService.createInvite(
            new ProjectInviteRequest("project-2", "guest@flowmat.local", "viewer")
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void acceptInviteCreatesActiveMember() {
        ProjectInvite invite = new ProjectInvite();
        invite.setInviteId("invite-3");
        invite.setProjectId("project-3");
        invite.setInvitedEmail("member@flowmat.local");
        invite.setProjectRole("editor");
        invite.setInviteStatus("pending");
        invite.setInviteToken("token-3");
        invite.setInvitedBy("owner-3");
        invite.setExpiredAt(OffsetDateTime.now().plusDays(1));

        Project project = project("project-3", "owner-3");
        UserView currentUser = user("member-3", "member@flowmat.local");

        when(projectInviteRepository.findByInviteToken("token-3")).thenReturn(Optional.of(invite));
        when(projectAccessService.requireCurrentUserId()).thenReturn("member-3");
        when(userQuery.findById("member-3")).thenReturn(Optional.of(currentUser));
        when(projectRepository.findByProjectIdAndDeletedYn("project-3", "N")).thenReturn(Optional.of(project));
        when(projectMemberRepository.findByProjectIdAndUserIdAndMemberStatus("project-3", "member-3", "active"))
            .thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("member-link-3");
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectInviteRepository.save(any(ProjectInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectMemberResponse response = projectInviteService.acceptInvite(new ProjectInviteAcceptRequest("token-3"));

        assertThat(response.projectMemberId()).isEqualTo("member-link-3");
        assertThat(response.projectId()).isEqualTo("project-3");
        assertThat(response.userId()).isEqualTo("member-3");
        assertThat(response.projectRole()).isEqualTo("editor");
        assertThat(response.memberStatus()).isEqualTo("active");
        assertThat(invite.getInviteStatus()).isEqualTo("accepted");
        assertThat(invite.getAcceptedAt()).isNotNull();
    }

    @Test
    void acceptInviteRejectsMismatchedEmail() {
        ProjectInvite invite = new ProjectInvite();
        invite.setInviteToken("token-4");
        invite.setProjectId("project-4");
        invite.setInvitedEmail("someone@flowmat.local");
        invite.setProjectRole("viewer");
        invite.setInviteStatus("pending");
        invite.setExpiredAt(OffsetDateTime.now().plusDays(1));

        UserView currentUser = user("member-4", "other@flowmat.local");

        when(projectInviteRepository.findByInviteToken("token-4")).thenReturn(Optional.of(invite));
        when(projectAccessService.requireCurrentUserId()).thenReturn("member-4");
        when(userQuery.findById("member-4")).thenReturn(Optional.of(currentUser));

        assertThatThrownBy(() -> projectInviteService.acceptInvite(new ProjectInviteAcceptRequest("token-4")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void previewShowsProjectRoleAndInviterForAddressee() {
        Project project = project("project-1", "owner-1");
        project.setProjectName("Line A");
        ProjectInvite invite = pendingInvite("guest@flowmat.local", OffsetDateTime.now().plusDays(3));
        UserView owner = new UserView("owner-1", "owner@flowmat.local", "Owner Kim");
        when(projectInviteRepository.findByInviteToken("token-1")).thenReturn(Optional.of(invite));
        when(projectRepository.findByProjectIdAndDeletedYn("project-1", "N")).thenReturn(Optional.of(project));
        when(userQuery.findById("owner-1")).thenReturn(Optional.of(owner));
        when(projectAccessService.requireCurrentUserId()).thenReturn("guest-1");
        when(userQuery.findById("guest-1")).thenReturn(Optional.of(user("guest-1", "GUEST@flowmat.local")));

        var preview = projectInviteService.previewInvite("token-1");

        assertThat(preview.projectName()).isEqualTo("Line A");
        assertThat(preview.projectRole()).isEqualTo("editor");
        assertThat(preview.inviterName()).isEqualTo("Owner Kim");
        assertThat(preview.invitedEmailMasked()).isEqualTo("gu***@flowmat.local");
        assertThat(preview.addressedToCurrentUser()).isTrue();
        assertThat(preview.expired()).isFalse();
    }

    @Test
    void previewFlagsOtherAccountAndExpiry() {
        ProjectInvite invite = pendingInvite("guest@flowmat.local", OffsetDateTime.now().minusDays(1));
        when(projectInviteRepository.findByInviteToken("token-1")).thenReturn(Optional.of(invite));
        when(projectRepository.findByProjectIdAndDeletedYn("project-1", "N")).thenReturn(Optional.of(project("project-1", "owner-1")));
        when(userQuery.findById("owner-1")).thenReturn(Optional.empty());
        when(projectAccessService.requireCurrentUserId()).thenReturn("someone-else");
        when(userQuery.findById("someone-else")).thenReturn(Optional.of(user("someone-else", "other@flowmat.local")));

        var preview = projectInviteService.previewInvite("token-1");

        assertThat(preview.addressedToCurrentUser()).isFalse();
        assertThat(preview.expired()).isTrue();
        assertThat(preview.inviterName()).isNull();
    }

    @Test
    void maskEmailHidesLocalPart() {
        assertThat(ProjectInviteServiceImpl.maskEmail("guest@flowmat.local")).isEqualTo("gu***@flowmat.local");
        assertThat(ProjectInviteServiceImpl.maskEmail("ab@x.io")).isEqualTo("a***@x.io");
        assertThat(ProjectInviteServiceImpl.maskEmail("not-an-email")).isEqualTo("***");
    }

    private static ProjectInvite pendingInvite(String invitedEmail, OffsetDateTime expiredAt) {
        ProjectInvite invite = new ProjectInvite();
        invite.setInviteId("invite-1");
        invite.setProjectId("project-1");
        invite.setInvitedEmail(invitedEmail);
        invite.setProjectRole("editor");
        invite.setInviteStatus("pending");
        invite.setInviteToken("token-1");
        invite.setInvitedBy("owner-1");
        invite.setExpiredAt(expiredAt);
        return invite;
    }

    private static Project project(String projectId, String ownerId) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setOwnerId(ownerId);
        project.setDeletedYn("N");
        return project;
    }

    private static UserView user(String userId, String userEmail) {
        return new UserView(userId, userEmail, userId);
    }
}
