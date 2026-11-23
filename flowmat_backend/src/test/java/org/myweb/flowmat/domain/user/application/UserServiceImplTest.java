package org.myweb.flowmat.domain.user.application;

import org.myweb.flowmat.domain.user.api.dto.request.UserUpdateRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.user.api.dto.request.AdminUserStatusUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.response.UserResponse;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.domain.enums.AdminUserActionType;
import org.myweb.flowmat.domain.user.domain.enums.UserStatus;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AuthRedisStore authRedisStore;

    @Mock
    private AdminUserActionLogService adminUserActionLogService;

    @InjectMocks
    private UserServiceImpl userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId("tester");
        user.setUserStatus("active");
        user.setDeleteYn("N");
    }

    @Test
    void aNewPasswordFromTheProfileSignsOutEveryDevice() {
        user.setUserPwd("encoded-old");
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Old-password-2026!", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("New-password-2026!")).thenReturn("encoded-new");

        userService.updateMe("tester", new UserUpdateRequest(null, null, null, null, null, null,
            "Old-password-2026!", "New-password-2026!"));

        assertThat(user.getUserPwd()).isEqualTo("encoded-new");
        verify(authRedisStore).revokeAllRefreshTokensOrFail("tester");
    }

    @Test
    void adminLockRevokesRefreshTokensAndWritesAuditLog() {
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));

        UserResponse response = userService.updateStatus(
            "tester",
            new AdminUserStatusUpdateRequest(UserStatus.LOCKED, "manual review")
        );

        verify(permissionService).require(SystemPermission.USER_MANAGE);
        verify(authRedisStore).revokeAllRefreshTokens("tester");
        verify(authRedisStore).clearLoginFailures("tester");
        verify(adminUserActionLogService).record(
            "tester",
            AdminUserActionType.STATUS_CHANGE,
            "ACTIVE",
            "LOCKED",
            "manual review"
        );
        assertThat(response.userStatus()).isEqualTo("locked");
        assertThat(user.getLockedAt()).isNotNull();
    }

    @Test
    void withdrawnUserCannotBeRestored() {
        user.setUserStatus("withdrawn");
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateStatus(
            "tester",
            new AdminUserStatusUpdateRequest(UserStatus.ACTIVE, "restore")
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.BAD_REQUEST);

        verify(authRedisStore, never()).revokeAllRefreshTokens("tester");
        verify(adminUserActionLogService, never()).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requestDormantRevokesRefreshTokensAndMarksDormant() {
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));

        userService.requestDormant("tester");

        verify(authRedisStore).revokeAllRefreshTokens("tester");
        verify(authRedisStore).clearLoginFailures("tester");
        assertThat(user.getUserStatus()).isEqualTo("dormant");
        assertThat(user.getDormantAt()).isNotNull();
        assertThat(user.getDormantToken()).isNull();
    }
}
