package org.myweb.flowmat.domain.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.user.api.dto.request.DormantReactivationRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.RefreshTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserLoginRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.mail.MailService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private AuthRedisStore authRedisStore;

    @Mock
    private MailService mailService;

    @InjectMocks
    private AuthServiceImpl authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId("tester");
        user.setUserName("Tester");
        user.setUserNickname("nick");
        user.setUserEmail("tester@example.com");
        user.setUserPwd("encoded-password");
        user.setUserRole("user");
        user.setUserStatus("active");
        user.setDeleteYn("N");
        user.setUserBirth(LocalDate.of(1990, 1, 1));
        user.setUserTel("010-1111-2222");
        user.setFailedLoginCount(0);
        user.setPwdUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void signupSavesVerifiedUser() {
        when(authRedisStore.isEmailVerified("tester@example.com")).thenReturn(true);
        when(passwordEncoder.encode("plain-password")).thenReturn("encoded-password");

        authService.signup(new UserSignupRequest(
            "tester",
            "Tester",
            "nick",
            "tester@example.com",
            "010-1111-2222",
            LocalDate.of(1990, 1, 1),
            "plain-password"
        ));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("tester");
        assertThat(saved.getUserNickname()).isEqualTo("nick");
        assertThat(saved.getEmailVerifiedYn()).isEqualTo("Y");
        verify(authRedisStore).clearEmailState("tester@example.com");
    }

    @Test
    void signupFailsWhenEmailNotVerified() {
        when(authRedisStore.isEmailVerified("tester@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.signup(new UserSignupRequest(
            "tester",
            "Tester",
            "nick",
            "tester@example.com",
            "010-1111-2222",
            LocalDate.of(1990, 1, 1),
            "plain-password"
        )))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginReturnsAccessAndRefreshTokens() {
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain-password", "encoded-password")).thenReturn(true);
        when(jwtProvider.generateAccessToken("tester", "user")).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken("tester")).thenReturn("refresh-token");
        when(jwtProvider.resolveJti("refresh-token")).thenReturn("jti-1");
        when(jwtProvider.getRefreshExpiryMs()).thenReturn(1000L);

        UserTokenResponse response = authService.login(
            new UserLoginRequest("tester", "plain-password", "device-1"),
            "JUnit",
            "127.0.0.1"
        );

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.deviceId()).isEqualTo("device-1");
        verify(authRedisStore).storeRefreshToken(anyString(), anyString(), anyString(), any());
        verify(authRedisStore).clearLoginFailures("tester");
    }

    @Test
    void loginToAnAccountThatDoesNotExistSpendsAPasswordCheckToo() {
        when(userRepository.findByUserId("nobody")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("unknown-user-hash");

        assertThatThrownBy(() -> authService.login(new UserLoginRequest("nobody", "plain-password", "device-1"), "JUnit", "127.0.0.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("User ID/email or password is incorrect.");
        verify(passwordEncoder).matches("plain-password", "unknown-user-hash");
    }

    @Test
    void refreshRotatesRefreshToken() {
        when(jwtProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtProvider.resolveUserId("refresh-token")).thenReturn("tester");
        when(jwtProvider.resolveJti("refresh-token")).thenReturn("jti-old");
        when(authRedisStore.consumeRefreshToken("jti-old", "tester"))
            .thenReturn(new AuthRedisStore.RefreshTokenEntry("tester", "device-1"));
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));
        when(jwtProvider.generateAccessToken("tester", "user")).thenReturn("access-token-2");
        when(jwtProvider.generateRefreshToken("tester")).thenReturn("refresh-token-2");
        when(jwtProvider.resolveJti("refresh-token-2")).thenReturn("jti-new");
        when(jwtProvider.getRefreshExpiryMs()).thenReturn(1000L);

        UserTokenResponse response = authService.refresh(
            new RefreshTokenRequest("refresh-token", "device-1"),
            null,
            "JUnit",
            "127.0.0.1"
        );

        assertThat(response.refreshToken()).isEqualTo("refresh-token-2");
        verify(authRedisStore).storeRefreshToken(org.mockito.Mockito.eq("jti-new"), org.mockito.Mockito.eq("tester"), org.mockito.Mockito.eq("device-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeGoogleSignupCreatesSocialAccountAndTokens() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get("provider")).thenReturn("google");
        when(claims.get("providerId")).thenReturn("google-123");
        when(claims.get("email")).thenReturn("tester@example.com");
        when(jwtProvider.validateOauthSignupToken("signup-token")).thenReturn(claims);
        when(passwordEncoder.encode("plain-password")).thenReturn("encoded-password");
        when(jwtProvider.generateAccessToken(anyString(), anyString())).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(jwtProvider.resolveJti("refresh-token")).thenReturn("jti-1");
        when(jwtProvider.getRefreshExpiryMs()).thenReturn(1000L);

        UserTokenResponse response = authService.completeGoogleSignup(new OAuthSignupCompleteRequest(
            "signup-token",
            "tester",
            "Tester",
            "nick",
            "010-1111-2222",
            LocalDate.of(1990, 1, 1),
            "plain-password"
        ), "JUnit", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(userRepository).save(any(User.class));
        verify(socialAccountRepository).save(any());
    }

    @Test
    void dormantUserCannotLogin() {
        user.setUserStatus("dormant");
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("plain-password", "encoded-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
            new UserLoginRequest("tester", "plain-password", "device-1"),
            "JUnit",
            "127.0.0.1"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DORMANT_ACCOUNT);
    }

    @Test
    void requestDormantReactivationSendsMailAndStoresToken() {
        user.setUserStatus("dormant");
        when(userRepository.findByUserId("tester")).thenReturn(Optional.of(user));

        authService.requestDormantReactivation(new DormantReactivationRequest("tester"));

        assertThat(user.getDormantToken()).isNotBlank();
        verify(mailService).sendDormantReactivationMail("tester@example.com", user.getDormantToken());
    }

    @Test
    void reactivateDormantClearsDormantFields() {
        user.setUserStatus("dormant");
        user.setDormantAt(OffsetDateTime.now());
        user.setDormantToken("reactivate-token");
        when(userRepository.findByDormantToken("reactivate-token")).thenReturn(Optional.of(user));

        authService.reactivateDormant(new DormantTokenRequest("reactivate-token"));

        assertThat(user.getUserStatus()).isEqualTo("active");
        assertThat(user.getDormantAt()).isNull();
        assertThat(user.getDormantToken()).isNull();
        verify(authRedisStore).revokeAllRefreshTokens("tester");
        verify(authRedisStore).clearLoginFailures("tester");
    }
}
