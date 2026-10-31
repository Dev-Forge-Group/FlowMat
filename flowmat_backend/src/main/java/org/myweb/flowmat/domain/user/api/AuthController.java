package org.myweb.flowmat.domain.user.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.api.dto.request.EmailCodeRequest;
import org.myweb.flowmat.domain.user.api.dto.request.EmailCodeVerifyRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantReactivationRequest;
import org.myweb.flowmat.domain.user.api.dto.request.DormantTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.FindEmailRequest;
import org.myweb.flowmat.domain.user.api.dto.request.LogoutRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthExchangeRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetConfirmRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetRequest;
import org.myweb.flowmat.domain.user.api.dto.request.RefreshTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkStartRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserLoginRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.api.dto.response.OAuthExchangeResponse;
import org.myweb.flowmat.domain.user.api.dto.response.SocialLinkStartResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;
import org.myweb.flowmat.domain.user.application.AuthService;
import org.myweb.flowmat.domain.user.application.AuthRedisStore;
import org.myweb.flowmat.domain.user.application.LoginRateLimitProperties;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.response.ApiResponse;
import org.myweb.flowmat.global.security.AuthUser;
import org.myweb.flowmat.global.security.JwtProvider;
import org.myweb.flowmat.global.security.RefreshTokenCookieService;
import org.myweb.flowmat.global.security.oauth.OAuthExchangeStore;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private static final String GUEST_SID_COOKIE_NAME = "guest_sid";
    private static final int GUEST_SID_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;
    private static final int EMAIL_CODE_IP_LIMIT = 10;
    private static final int EMAIL_CODE_EMAIL_LIMIT = 5;
    private static final int DORMANT_REQUEST_IP_LIMIT = 8;
    private static final int DORMANT_REQUEST_ACCOUNT_LIMIT = 5;
    private static final Duration LOGIN_RATE_WINDOW = Duration.ofMinutes(10);
    private static final Duration EMAIL_CODE_RATE_WINDOW = Duration.ofHours(1);
    private static final Duration DORMANT_REQUEST_RATE_WINDOW = Duration.ofHours(1);

    private final AuthService authService;
    private final AuthRedisStore authRedisStore;
    private final LoginRateLimitProperties loginRateLimitProperties;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final OAuthExchangeStore oauthExchangeStore;
    private final JwtProvider jwtProvider;

    @PostMapping("/email/send-code")
    public ApiResponse<Void> sendEmailCode(HttpServletRequest httpRequest, @Valid @RequestBody EmailCodeRequest request) {
        enforceRateLimit("email-code-ip", clientIpKey(httpRequest), EMAIL_CODE_IP_LIMIT, EMAIL_CODE_RATE_WINDOW);
        enforceRateLimit("email-code-email", normalizeRateKey(request.userEmail()), EMAIL_CODE_EMAIL_LIMIT, EMAIL_CODE_RATE_WINDOW);
        authService.sendEmailCode(request.userEmail());
        return ApiResponse.ok(null);
    }

    @PostMapping("/email/verify-code")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody EmailCodeVerifyRequest request) {
        authService.verifyEmailCode(request.userEmail(), request.code());
        return ApiResponse.ok(null);
    }

    @PostMapping("/dormant/reactivation/request")
    public ApiResponse<Void> requestDormantReactivation(
        HttpServletRequest httpRequest,
        @Valid @RequestBody DormantReactivationRequest request
    ) {
        enforceRateLimit("dormant-reactivation-ip", clientIpKey(httpRequest), DORMANT_REQUEST_IP_LIMIT, DORMANT_REQUEST_RATE_WINDOW);
        enforceRateLimit(
            "dormant-reactivation-account",
            normalizeRateKey(request.userIdOrEmail()),
            DORMANT_REQUEST_ACCOUNT_LIMIT,
            DORMANT_REQUEST_RATE_WINDOW
        );
        authService.requestDormantReactivation(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/dormant/reactivate")
    public ApiResponse<Void> reactivateDormant(@Valid @RequestBody DormantTokenRequest request) {
        authService.reactivateDormant(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/signup")
    public ApiResponse<Void> signup(@Valid @RequestBody UserSignupRequest request) {
        authService.signup(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/check-nickname")
    public ApiResponse<Boolean> checkNickname(@RequestParam("nickname") String nickname) {
        return ApiResponse.ok(authService.checkNicknameAvailable(nickname));
    }

    @PostMapping("/login")
    public ApiResponse<UserTokenResponse> login(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        @Valid @RequestBody UserLoginRequest request
    ) {
        enforceRateLimit("login-ip", clientIpKey(httpRequest), loginRateLimitProperties.getIpLimit(), LOGIN_RATE_WINDOW);
        enforceRateLimit("login-account", normalizeRateKey(request.userIdOrEmail()), loginRateLimitProperties.getAccountLimit(), LOGIN_RATE_WINDOW);
        UserTokenResponse response = authService.login(request, httpRequest.getHeader("User-Agent"), extractIp(httpRequest));
        writeRefreshCookie(httpRequest, httpResponse, response);
        return ApiResponse.ok(cookieBackedResponse(response));
    }

    @GetMapping("/csrf")
    public ApiResponse<String> csrfToken(CsrfToken csrfToken) {
        return ApiResponse.ok(csrfToken.getToken());
    }

    @PostMapping("/guest-token")
    public ApiResponse<UserTokenResponse> guestToken(HttpServletRequest request, HttpServletResponse response) {
        String guestSid = resolveOrCreateGuestSid(request);
        UserTokenResponse token = authService.issueGuestToken(guestSid, extractBearer(request.getHeader(HttpHeaders.AUTHORIZATION)));
        setGuestSidCookie(response, guestSid, isSecureRequest(request));
        return ApiResponse.ok(token);
    }

    @PostMapping("/refresh")
    public ApiResponse<UserTokenResponse> refresh(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody(required = false) RefreshTokenRequest request
    ) {
        String explicitRefreshToken = request != null && request.refreshToken() != null
            ? request.refreshToken()
            : extractBearer(authorization);
        String cookieRefreshToken = refreshTokenCookieService.resolveRefreshToken(httpRequest);
        boolean cookieBacked = (explicitRefreshToken == null || explicitRefreshToken.isBlank())
            && cookieRefreshToken != null
            && !cookieRefreshToken.isBlank();
        UserTokenResponse response = authService.refresh(
            request,
            explicitRefreshToken != null && !explicitRefreshToken.isBlank() ? explicitRefreshToken : cookieRefreshToken,
            httpRequest.getHeader("User-Agent"),
            extractIp(httpRequest)
        );
        writeRefreshCookie(httpRequest, httpResponse, response);
        if (cookieBacked) {
            return ApiResponse.ok(cookieBackedResponse(response));
        }
        return ApiResponse.ok(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody(required = false) LogoutRequest request
    ) {
        String refreshToken = request != null && request.refreshToken() != null
            ? request.refreshToken()
            : extractBearer(authorization);
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = refreshTokenCookieService.resolveRefreshToken(httpRequest);
        }
        authService.logout(refreshToken);
        refreshTokenCookieService.clearRefreshToken(httpRequest, httpResponse);
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(@AuthenticationPrincipal AuthUser authUser) {
        requireAuth(authUser);
        authService.logoutAll(authUser.getUserId());
        return ApiResponse.ok(null);
    }

    @PostMapping("/oauth2/kakao/complete")
    public ApiResponse<UserTokenResponse> completeKakaoSignup(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        @Valid @RequestBody OAuthSignupCompleteRequest request
    ) {
        UserTokenResponse response = authService.completeKakaoSignup(request, httpRequest.getHeader("User-Agent"), extractIp(httpRequest));
        writeRefreshCookie(httpRequest, httpResponse, response);
        return ApiResponse.ok(cookieBackedResponse(response));
    }

    @PostMapping("/oauth2/google/complete")
    public ApiResponse<UserTokenResponse> completeGoogleSignup(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse,
        @Valid @RequestBody OAuthSignupCompleteRequest request
    ) {
        UserTokenResponse response = authService.completeGoogleSignup(request, httpRequest.getHeader("User-Agent"), extractIp(httpRequest));
        writeRefreshCookie(httpRequest, httpResponse, response);
        return ApiResponse.ok(cookieBackedResponse(response));
    }

    @PostMapping("/oauth2/exchange")
    public ApiResponse<OAuthExchangeResponse> exchangeOAuthResult(@Valid @RequestBody OAuthExchangeRequest request) {
        OAuthExchangeStore.OAuthExchangeEntry result = oauthExchangeStore.consume(request.code().trim());
        if (result == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "OAuth exchange code is invalid or expired.");
        }
        return ApiResponse.ok(new OAuthExchangeResponse(
            result.resultType(),
            result.accessToken(),
            result.signupToken(),
            result.provider(),
            result.deviceId(),
            result.additionalInfoRequired()
        ));
    }

    @PostMapping("/oauth2/link/start")
    public ApiResponse<SocialLinkStartResponse> startSocialLink(
        @AuthenticationPrincipal AuthUser authUser,
        @Valid @RequestBody SocialLinkStartRequest request
    ) {
        requireAuth(authUser);
        return ApiResponse.ok(authService.startSocialLink(authUser.getUserId(), request));
    }

    @PostMapping("/find-email")
    public ApiResponse<String> findEmail(@Valid @RequestBody FindEmailRequest request) {
        return ApiResponse.ok(authService.findEmail(request));
    }

    @PostMapping("/reset-password/request")
    public ApiResponse<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reset-password/verify")
    public ApiResponse<Void> verifyPasswordResetToken(@RequestParam("token") String token) {
        authService.verifyPasswordResetToken(token);
        return ApiResponse.ok(null);
    }

    @PostMapping("/reset-password/confirm")
    public ApiResponse<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.confirmPasswordReset(request);
        return ApiResponse.ok(null);
    }

    private void requireAuth(AuthUser authUser) {
        if (authUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    private String extractIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveOrCreateGuestSid(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (GUEST_SID_COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null) {
                    String sid = jwtProvider.resolveGuestSessionId(cookie.getValue());
                    if (sid != null) return sid;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void setGuestSidCookie(HttpServletResponse response, String guestSid, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(GUEST_SID_COOKIE_NAME,
                jwtProvider.generateGuestSessionToken(guestSid, GUEST_SID_MAX_AGE_SECONDS * 1000L))
            .httpOnly(true)
            .secure(secure)
            .path("/")
            .sameSite("Lax")
            .maxAge(GUEST_SID_MAX_AGE_SECONDS)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.trim());
    }

    private void writeRefreshCookie(HttpServletRequest request, HttpServletResponse response, UserTokenResponse tokenResponse) {
        if (tokenResponse != null && tokenResponse.refreshToken() != null && !tokenResponse.refreshToken().isBlank()) {
            refreshTokenCookieService.writeRefreshToken(request, response, tokenResponse.refreshToken());
        }
    }

    private UserTokenResponse cookieBackedResponse(UserTokenResponse response) {
        return new UserTokenResponse(
            response.accessToken(),
            null,
            response.deviceId(),
            response.additionalInfoRequired()
        );
    }

    private void enforceRateLimit(String category, String subject, int limit, Duration window) {
        if (!authRedisStore.tryAcquireRateLimit(category, subject, limit, window)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    private String clientIpKey(HttpServletRequest request) {
        return normalizeRateKey(extractIp(request));
    }

    private String normalizeRateKey(String raw) {
        String value = raw == null ? "unknown" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return "unknown";
        }
        return value.replace('|', '_').replace(' ', '_');
    }
}
