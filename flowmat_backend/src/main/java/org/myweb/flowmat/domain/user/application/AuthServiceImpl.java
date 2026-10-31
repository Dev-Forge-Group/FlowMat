package org.myweb.flowmat.domain.user.application;

import io.jsonwebtoken.Claims;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.request.FindEmailRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantReactivationRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetConfirmRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetRequest;
import org.myweb.flowmat.domain.user.api.dto.request.RefreshTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkStartRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserLoginRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.api.dto.response.SocialLinkStartResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.mail.MailService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private static final int LOGIN_FAIL_THRESHOLD = 5;
    private static final long GUEST_ACCESS_EXPIRY_MS = 20 * 60 * 1000L;
    private static final long GUEST_REISSUE_THRESHOLD_MS = 3 * 60 * 1000L;
    private static final String GUEST_ROLE = "guest";
    private static final String GUEST_USER_ID_PREFIX = "GUEST_";
    private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(5);
    private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(5);
    private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(5);
    private static final Duration EMAIL_CODE_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration EMAIL_VERIFIED_TTL = Duration.ofMinutes(30);
    private static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final AuthRedisStore authRedisStore;
    private final MailService mailService;

    @Override
    @Transactional(readOnly = true)
    public void sendEmailCode(String userEmail) {
        String normalizedEmail = normalizeEmail(userEmail);
        if (normalizedEmail == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Email is required.");
        }
        if (userRepository.existsByUserEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String code = authRedisStore.generateVerificationCode();
        try {
            authRedisStore.saveEmailCode(normalizedEmail, code, EMAIL_CODE_TTL, EMAIL_CODE_COOLDOWN);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_COOLDOWN);
        }
        mailService.sendEmailVerificationCode(normalizedEmail, code);
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyEmailCode(String userEmail, String code) {
        String normalizedEmail = normalizeEmail(userEmail);
        if (normalizedEmail == null || code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!authRedisStore.verifyEmailCode(normalizedEmail, code.trim(), EMAIL_VERIFIED_TTL)) {
            throw new BusinessException(ErrorCode.EMAIL_CODE_INVALID);
        }
    }

    @Override
    public void signup(UserSignupRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String userId = normalizeSignupUserId(request.userId());
        String userName = normalizeRequired(request.userName(), "User name is required.");
        String nickname = normalizeRequired(request.userNickname(), "Nickname is required.");
        String email = normalizeEmail(request.userEmail());
        String tel = normalizeRequired(request.userTel(), "Phone number is required.");
        String password = normalizeRequired(request.password(), "Password is required.");
        if (request.userBirth() == null || email == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        if (!authRedisStore.isEmailVerified(email)) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        ensureUnique(userId, email, tel, nickname);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId(userId);
        user.setUserName(userName);
        user.setUserNickname(nickname);
        user.setUserEmail(email);
        user.setUserPwd(passwordEncoder.encode(password));
        user.setUserBirth(request.userBirth());
        user.setUserTel(tel);
        user.setUserRole("user");
        user.setUserStatus("active");
        user.setDeleteYn("N");
        user.setEmailVerifiedYn("Y");
        user.setEmailVerifiedAt(OffsetDateTime.now());
        user.setFailedLoginCount(0);
        user.setPwdUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);
        authRedisStore.clearEmailState(email);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkNicknameAvailable(String nickname) {
        String normalizedNickname = normalizeRequired(nickname, "Nickname is required.");
        if (userRepository.existsByUserNickname(normalizedNickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        return true;
    }

    @Override
    public UserTokenResponse login(UserLoginRequest request, String userAgent, String ipAddress) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String userIdOrEmail = normalizeRequired(request.userIdOrEmail(), "User ID or email is required.");
        String password = normalizeRequired(request.password(), "Password is required.");

        User user = resolveLoginUser(userIdOrEmail);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User ID/email or password is incorrect.");
        }
        if (authRedisStore.isLoginLocked(user.getUserId())) {
            user.setLockedAt(OffsetDateTime.now());
            throw new BusinessException(ErrorCode.FORBIDDEN, "Account is temporarily locked. Try again later.");
        }
        if (!passwordEncoder.matches(password, user.getUserPwd())) {
            boolean locked = authRedisStore.recordLoginFailure(
                user.getUserId(),
                LOGIN_FAIL_THRESHOLD,
                LOGIN_FAIL_WINDOW,
                LOGIN_LOCK_DURATION
            );
            user.setFailedLoginCount((user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1);
            if (locked) {
                user.setLockedAt(OffsetDateTime.now());
                throw new BusinessException(ErrorCode.FORBIDDEN, "Too many failed login attempts. Account is temporarily locked.");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User ID/email or password is incorrect.");
        }
        if (isDormant(user)) {
            throw new BusinessException(ErrorCode.DORMANT_ACCOUNT, "This account is dormant. Request reactivation to continue.");
        }
        if (!canLogin(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This account cannot log in.");
        }

        authRedisStore.clearLoginFailures(user.getUserId());
        user.setFailedLoginCount(0);
        user.setLockedAt(null);
        user.setLastLoginAt(OffsetDateTime.now());

        return issueTokens(user, resolveDeviceId(request.deviceId()));
    }

    @Override
    @Transactional(readOnly = true)
    public UserTokenResponse issueGuestToken(String guestSid, String currentAccessToken) {
        String normalizedSid = normalizeRequired(guestSid, "Guest session ID is required.").replace("-", "");
        if (!normalizedSid.matches("[0-9a-f]{32}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Guest session ID is invalid.");
        }
        String guestUserId = GUEST_USER_ID_PREFIX + normalizedSid;
        if (userRepository.existsByUserId(guestUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Guest identity conflicts with a registered user.");
        }
        String accessToken = normalizeNullable(currentAccessToken);

        if (hasText(accessToken)) {
            try {
                jwtProvider.validate(accessToken);
                boolean validGuestToken = jwtProvider.isAccessToken(accessToken)
                    && GUEST_ROLE.equalsIgnoreCase(jwtProvider.resolveRole(accessToken))
                    && guestUserId.equals(jwtProvider.resolveUserId(accessToken));
                if (!validGuestToken) {
                    accessToken = null;
                }
            } catch (Exception ignored) {
                accessToken = null;
            }
        }

        if (!hasText(accessToken) || shouldReissueGuestToken(accessToken)) {
            accessToken = jwtProvider.generateGuestAccessToken(guestUserId, GUEST_ACCESS_EXPIRY_MS);
        }

        return new UserTokenResponse(accessToken, null, normalizedSid, false);
    }

    @Override
    @Transactional(readOnly = true)
    public UserTokenResponse refresh(RefreshTokenRequest request, String fallbackRefreshToken, String userAgent, String ipAddress) {
        String refreshToken = request != null && hasText(request.refreshToken()) ? request.refreshToken().trim() : normalizeNullable(fallbackRefreshToken);
        String requestedDeviceId = request == null ? null : normalizeNullable(request.deviceId());
        if (!hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        jwtProvider.validate(refreshToken);
        if (!jwtProvider.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_TYPE_INVALID);
        }

        String userId = jwtProvider.resolveUserId(refreshToken);
        String jti = jwtProvider.resolveJti(refreshToken);
        AuthRedisStore.RefreshTokenEntry entry = authRedisStore.consumeRefreshToken(jti, userId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        if (!userId.equals(entry.userId())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (hasText(requestedDeviceId) && hasText(entry.deviceId()) && !requestedDeviceId.equals(entry.deviceId())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!canLogin(user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return issueTokens(user, entry.deviceId());
    }

    @Override
    @Transactional(readOnly = true)
    public void logout(String refreshToken) {
        if (!hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        jwtProvider.validate(refreshToken);
        if (!jwtProvider.isRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_TYPE_INVALID);
        }
        authRedisStore.revokeRefreshToken(jwtProvider.resolveJti(refreshToken), jwtProvider.resolveUserId(refreshToken));
    }

    @Override
    public void logoutAll(String userId) {
        authRedisStore.revokeAllRefreshTokens(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public String findEmail(FindEmailRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        User user = userRepository.findByUserNameAndUserTel(
                normalizeRequired(request.userName(), "User name is required."),
                normalizeRequired(request.userTel(), "Phone number is required.")
            )
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        return maskEmail(user.getUserEmail());
    }

    @Override
    public void requestPasswordReset(PasswordResetRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String email = normalizeEmail(request.userEmail());
        if (email == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        User user = userRepository.findByUserEmail(email).orElse(null);
        if (user == null) {
            return;
        }
        String token = authRedisStore.createPasswordResetToken(user.getUserId(), PASSWORD_RESET_TTL);
        mailService.sendPasswordResetMail(email, token);
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyPasswordResetToken(String token) {
        if (!hasText(token)) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
        }
        if (!hasText(authRedisStore.getPasswordResetUserId(token.trim()))) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        }
    }

    @Override
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        if (request == null || !hasText(request.token()) || !hasText(request.newPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String userId = authRedisStore.getPasswordResetUserId(request.token().trim());
        if (!hasText(userId)) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        }
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        user.setUserPwd(passwordEncoder.encode(request.newPassword().trim()));
        user.setPwdUpdatedAt(OffsetDateTime.now());
        authRedisStore.consumePasswordResetToken(request.token().trim());
    }

    @Override
    public void requestDormantReactivation(DormantReactivationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        String userIdOrEmail = normalizeRequired(request.userIdOrEmail(), "User ID or email is required.");
        User user = resolveLoginUser(userIdOrEmail);
        if (user == null || !isDormant(user)) {
            return;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        user.setDormantToken(token);
        mailService.sendDormantReactivationMail(user.getUserEmail(), token);
    }

    @Override
    public void reactivateDormant(DormantTokenRequest request) {
        if (request == null || !hasText(request.token())) {
            throw new BusinessException(ErrorCode.DORMANT_TOKEN_INVALID);
        }
        User user = userRepository.findByDormantToken(request.token().trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.DORMANT_TOKEN_INVALID));
        if (!isDormant(user)) {
            throw new BusinessException(ErrorCode.DORMANT_TOKEN_INVALID);
        }
        user.setUserStatus("active");
        user.setDormantAt(null);
        user.setDormantToken(null);
        user.setLockedAt(null);
        authRedisStore.revokeAllRefreshTokens(user.getUserId());
        authRedisStore.clearLoginFailures(user.getUserId());
    }

    @Override
    public UserTokenResponse completeKakaoSignup(OAuthSignupCompleteRequest request, String userAgent, String ipAddress) {
        return completeOAuthSignup("kakao", request);
    }

    @Override
    public UserTokenResponse completeGoogleSignup(OAuthSignupCompleteRequest request, String userAgent, String ipAddress) {
        return completeOAuthSignup("google", request);
    }

    @Override
    @Transactional(readOnly = true)
    public SocialLinkStartResponse startSocialLink(String userId, SocialLinkStartRequest request) {
        if (!hasText(userId) || request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found."));
        if (!passwordEncoder.matches(normalizeRequired(request.currentPassword(), "Current password is required."), user.getUserPwd())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        String provider = normalizeProvider(request.provider());
        String returnTo = sanitizeReturnPath(request.returnTo());
        String linkToken = jwtProvider.generateOauthLinkToken(user.getUserId(), provider.toLowerCase());
        String authorizationUrl = "/api/oauth2/authorization/"
            + provider.toLowerCase()
            + "?mode=link&linkToken=" + enc(linkToken)
            + "&returnTo=" + enc(returnTo);
        return new SocialLinkStartResponse(authorizationUrl);
    }

    private UserTokenResponse completeOAuthSignup(String expectedProvider, OAuthSignupCompleteRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        Claims claims = jwtProvider.validateOauthSignupToken(request.signupToken());
        String provider = normalizeRequired(asText(claims.get("provider")), "Provider is missing.").toLowerCase();
        if (!expectedProvider.equals(provider)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "OAuth provider does not match.");
        }
        String providerId = normalizeRequired(asText(claims.get("providerId")), "Provider user ID is missing.");
        String providerEmail = normalizeEmail(asText(claims.get("email")));

        if (socialAccountRepository.existsByProviderAndProviderUserId(provider.toUpperCase(), providerId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This social account is already linked.");
        }

        String userId = normalizeSignupUserId(request.userId());
        String userName = normalizeRequired(request.userName(), "User name is required.");
        String nickname = normalizeRequired(request.userNickname(), "Nickname is required.");
        String tel = normalizeRequired(request.userTel(), "Phone number is required.");
        String password = normalizeRequired(request.password(), "Password is required.");
        if (request.userBirth() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Birth date is required.");
        }
        ensureUnique(userId, null, tel, nickname);

        String email = resolveSocialEmail(provider, providerId, providerEmail);
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUserId(userId);
        user.setUserName(userName);
        user.setUserNickname(nickname);
        user.setUserEmail(email);
        user.setUserPwd(passwordEncoder.encode(password));
        user.setUserBirth(request.userBirth());
        user.setUserTel(tel);
        user.setUserRole("user");
        user.setUserStatus("active");
        user.setDeleteYn("N");
        user.setEmailVerifiedYn(providerEmail != null ? "Y" : "N");
        user.setEmailVerifiedAt(providerEmail != null ? OffsetDateTime.now() : null);
        user.setFailedLoginCount(0);
        user.setPwdUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        SocialAccount socialAccount = new SocialAccount();
        socialAccount.setUser(user);
        socialAccount.setProvider(provider.toUpperCase());
        socialAccount.setProviderUserId(providerId);
        socialAccount.setProviderEmail(providerEmail);
        socialAccountRepository.save(socialAccount);

        return issueTokens(user, provider);
    }

    private UserTokenResponse issueTokens(User user, String deviceId) {
        String normalizedDeviceId = hasText(deviceId) ? deviceId.trim() : resolveDeviceId(null);
        String accessToken = jwtProvider.generateAccessToken(user.getUserId(), normalizeRole(user.getUserRole()));
        String refreshToken = jwtProvider.generateRefreshToken(user.getUserId());
        String jti = jwtProvider.resolveJti(refreshToken);
        authRedisStore.storeRefreshToken(jti, user.getUserId(), normalizedDeviceId, Duration.ofMillis(jwtProvider.getRefreshExpiryMs()));
        return new UserTokenResponse(accessToken, refreshToken, normalizedDeviceId, isAdditionalInfoRequired(user));
    }

    private String normalizeSignupUserId(String value) {
        String userId = normalizeRequired(value, "User ID is required.");
        if (userId.regionMatches(true, 0, GUEST_USER_ID_PREFIX, 0, GUEST_USER_ID_PREFIX.length())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId uses a reserved prefix.");
        }
        return userId;
    }

    private void ensureUnique(String userId, String email, String tel, String nickname) {
        if (userRepository.existsByUserId(userId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USER_ID);
        }
        if (email != null && userRepository.existsByUserEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByUserTel(tel)) {
            throw new BusinessException(ErrorCode.DUPLICATE_TEL);
        }
        if (userRepository.existsByUserNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }

    private User resolveLoginUser(String userIdOrEmail) {
        if (userIdOrEmail.contains("@")) {
            return userRepository.findByUserEmail(normalizeEmail(userIdOrEmail)).orElse(null);
        }
        return userRepository.findByUserId(userIdOrEmail.trim()).orElse(null);
    }

    private boolean canLogin(User user) {
        if (user == null) {
            return false;
        }
        return !"Y".equalsIgnoreCase(user.getDeleteYn())
            && !"dormant".equalsIgnoreCase(user.getUserStatus())
            && !"withdrawn".equalsIgnoreCase(user.getUserStatus())
            && !"locked".equalsIgnoreCase(user.getUserStatus());
    }

    private boolean isDormant(User user) {
        return user != null && "dormant".equalsIgnoreCase(user.getUserStatus());
    }

    private boolean isAdditionalInfoRequired(User user) {
        return user == null
            || !hasText(user.getUserNickname())
            || !hasText(user.getUserTel())
            || user.getUserBirth() == null;
    }

    private String resolveSocialEmail(String provider, String providerId, String providerEmail) {
        if (providerEmail != null && !userRepository.existsByUserEmail(providerEmail)) {
            return providerEmail;
        }
        String base = provider + "_" + providerId + "@social.local";
        if (!userRepository.existsByUserEmail(base)) {
            return base;
        }
        return provider + "_" + providerId + "_" + UUID.randomUUID().toString().substring(0, 8) + "@social.local";
    }

    private String resolveDeviceId(String deviceId) {
        return hasText(deviceId) ? deviceId.trim() : "web-" + UUID.randomUUID();
    }

    private boolean shouldReissueGuestToken(String token) {
        try {
            String typ = jwtProvider.resolveTokenType(token);
            String role = jwtProvider.resolveRole(token);
            if (!"access".equals(typ) || !GUEST_ROLE.equalsIgnoreCase(role)) {
                return true;
            }
            java.util.Date expiration = jwtProvider.resolveExpiration(token);
            if (expiration == null) {
                return true;
            }
            long remaining = expiration.getTime() - System.currentTimeMillis();
            return remaining <= GUEST_REISSUE_THRESHOLD_MS;
        } catch (Exception e) {
            return true;
        }
    }

    private String normalizeProvider(String provider) {
        String normalized = normalizeRequired(provider, "Provider is required.").toUpperCase();
        if (!"KAKAO".equals(normalized) && !"GOOGLE".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported social provider.");
        }
        return normalized;
    }

    private String sanitizeReturnPath(String returnTo) {
        if (!hasText(returnTo) || !returnTo.trim().startsWith("/")) {
            return "/settings/account";
        }
        return returnTo.trim();
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    private String normalizeEmail(String email) {
        return hasText(email) ? email.trim().toLowerCase() : null;
    }

    private String normalizeRequired(String value, String message) {
        if (!hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String normalizeRole(String role) {
        return hasText(role) ? role.trim().toLowerCase() : "user";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
