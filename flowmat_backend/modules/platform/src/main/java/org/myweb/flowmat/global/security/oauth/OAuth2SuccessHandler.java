package org.myweb.flowmat.global.security.oauth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.user.application.AuthRedisStore;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.myweb.flowmat.global.security.RefreshTokenCookieService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final String LINK_STATE_PREFIX = "link.";
    private static final Duration EXCHANGE_TTL = Duration.ofMinutes(5);

    private final JwtProvider jwtProvider;
    private final AuthRedisStore authRedisStore;
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final OAuthExchangeStore oauthExchangeStore;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final OAuth2FailureHandler oAuth2FailureHandler;

    @Value("${app.oauth2.redirect-uri:}")
    private String redirectUri;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException, ServletException {
        UserContext userContext = (UserContext) authentication.getPrincipal();
        LinkState linkState = parseLinkState(request.getParameter("state"));
        if (linkState != null) {
            handleLinkFlow(request, response, userContext, linkState);
            return;
        }

        String resolvedRedirectUri = resolveRedirectUri(request);
        if (userContext.isSignupRequired()) {
            String signupToken = jwtProvider.generateOauthSignupToken(
                userContext.getProvider().toLowerCase(),
                userContext.getProviderId(),
                userContext.getEmail(),
                userContext.getNickname()
            );
            String exchangeCode = oauthExchangeStore.issueCode(
                new OAuthExchangeStore.OAuthExchangeEntry(
                    OAuthExchangeStore.OAuthExchangeEntry.SIGNUP_REQUIRED,
                    null,
                    signupToken,
                    userContext.getProvider().toLowerCase(),
                    null,
                    true
                ),
                EXCHANGE_TTL
            );
            response.sendRedirect(
                resolvedRedirectUri
                    + "?code=" + enc(exchangeCode)
            );
            return;
        }

        User user = userContext.getUser();
        if (!canLogin(user)) {
            // The same refusal as when the provider's user is loaded (CustomOAuth2UserService): back to the front end with
            // the reason and nothing issued. An exception here would not reach the failure handler.
            oAuth2FailureHandler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "blocked_user"));
            return;
        }
        String accessToken = jwtProvider.generateAccessToken(user.getUserId(), user.getUserRole());
        String refreshToken = jwtProvider.generateRefreshToken(user.getUserId());
        String jti = jwtProvider.resolveJti(refreshToken);
        String deviceId = "oauth-" + userContext.getProvider().toLowerCase();
        authRedisStore.storeRefreshToken(jti, user.getUserId(), deviceId, Duration.ofMillis(jwtProvider.getRefreshExpiryMs()));
        refreshTokenCookieService.writeRefreshToken(request, response, refreshToken);
        String exchangeCode = oauthExchangeStore.issueCode(
            new OAuthExchangeStore.OAuthExchangeEntry(
                OAuthExchangeStore.OAuthExchangeEntry.LOGIN,
                accessToken,
                null,
                userContext.getProvider().toLowerCase(),
                deviceId,
                isAdditionalInfoRequired(user)
            ),
            EXCHANGE_TTL
        );

        response.sendRedirect(
            resolvedRedirectUri
                + "?code=" + enc(exchangeCode)
        );
    }

    private void handleLinkFlow(
        HttpServletRequest request,
        HttpServletResponse response,
        UserContext userContext,
        LinkState linkState
    ) throws IOException {
        String returnTo = sanitizeReturnPath(linkState.returnTo());
        String provider = userContext.getProvider() == null ? null : userContext.getProvider().toUpperCase();
        String providerId = userContext.getProviderId();

        try {
            Claims claims = jwtProvider.validateOauthLinkToken(linkState.linkToken());
            String userId = String.valueOf(claims.get("userId"));
            String expectedProvider = String.valueOf(claims.get("provider"));
            if (provider == null || providerId == null || !provider.equalsIgnoreCase(expectedProvider)) {
                response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linkError", "provider_mismatch")));
                return;
            }

            User target = userRepository.findByUserId(userId).orElse(null);
            if (target == null || !canLogin(target)) {
                response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linkError", "target_user_invalid")));
                return;
            }

            SocialAccount existing = socialAccountRepository
                .findByProviderAndProviderUserId(provider, providerId)
                .orElse(null);
            if (existing != null) {
                if (!existing.getUser().getUserId().equals(target.getUserId())) {
                    response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linkError", "already_linked_other_user")));
                    return;
                }
                response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linked", provider.toLowerCase())));
                return;
            }

            SocialAccount socialAccount = new SocialAccount();
            socialAccount.setUser(target);
            socialAccount.setProvider(provider);
            socialAccount.setProviderUserId(providerId);
            socialAccount.setProviderEmail(userContext.getEmail());
            socialAccountRepository.save(socialAccount);

            response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linked", provider.toLowerCase())));
        } catch (Exception e) {
            response.sendRedirect(buildFrontendUrl(request, appendQuery(returnTo, "linkError", "link_failed")));
        }
    }

    private boolean canLogin(User user) {
        return user != null
            && !"Y".equalsIgnoreCase(user.getDeleteYn())
            && !"withdrawn".equalsIgnoreCase(user.getUserStatus())
            && !"locked".equalsIgnoreCase(user.getUserStatus())
            && !"dormant".equalsIgnoreCase(user.getUserStatus());
    }

    private boolean isAdditionalInfoRequired(User user) {
        return user == null
            || user.getUserNickname() == null || user.getUserNickname().isBlank()
            || user.getUserTel() == null || user.getUserTel().isBlank()
            || user.getUserBirth() == null;
    }

    private LinkState parseLinkState(String state) {
        if (state == null || !state.startsWith(LINK_STATE_PREFIX)) {
            return null;
        }
        int separator = state.indexOf('.', LINK_STATE_PREFIX.length());
        if (separator < 0) {
            return null;
        }
        try {
            String payload = state.substring(LINK_STATE_PREFIX.length(), separator);
            String raw = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] tokens = raw.split("\\n", 2);
            return tokens.length == 2 ? new LinkState(tokens[0], tokens[1]) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sanitizeReturnPath(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/settings/account";
        }
        return returnTo.trim();
    }

    private String appendQuery(String path, String key, String value) {
        return path + (path.contains("?") ? "&" : "?") + key + "=" + enc(value);
    }

    private String buildFrontendUrl(HttpServletRequest request, String path) {
        String base = frontendUrl == null || frontendUrl.isBlank() ? resolveRequestOrigin(request) : frontendUrl;
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    private String resolveRedirectUri(HttpServletRequest request) {
        if (redirectUri != null && !redirectUri.isBlank()) {
            return redirectUri;
        }
        return resolveRequestOrigin(request) + "/oauth2/success";
    }

    private String resolveRequestOrigin(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        return proto + "://" + host;
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record LinkState(String linkToken, String returnTo) {
    }
}
