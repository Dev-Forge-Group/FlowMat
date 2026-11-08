package org.myweb.flowmat.global.security.oauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.domain.user.application.AuthRedisStore;
import org.myweb.flowmat.domain.user.domain.entity.SocialAccount;
import org.myweb.flowmat.domain.user.domain.entity.User;
import org.myweb.flowmat.domain.user.repository.SocialAccountRepository;
import org.myweb.flowmat.domain.user.repository.UserRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.myweb.flowmat.global.security.RefreshTokenCookieService;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.client.RestOperations;

class OAuthAccountStatusRegressionTest {
    private final SocialAccountRepository accounts = mock(SocialAccountRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtProvider jwt = mock(JwtProvider.class);
    private final AuthRedisStore sessions = mock(AuthRedisStore.class);
    private final OAuthExchangeStore exchanges = mock(OAuthExchangeStore.class);
    private final RefreshTokenCookieService cookies = mock(RefreshTokenCookieService.class);
    private final OAuth2FailureHandler failure = failureHandler();

    @ParameterizedTest
    @CsvSource({"dormant,N", "locked,N", "withdrawn,N", "active,Y"})
    void linkedBlockedAccountCannotBecomeAuthenticated(String status, String deleted) {
        var service = serviceFor(user(status, deleted));
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request()));
    }

    @ParameterizedTest
    @CsvSource({"dormant,N", "locked,N", "withdrawn,N", "active,Y"})
    void successCallbackCannotIssueCredentialsForBlockedPrincipal(String status, String deleted) throws Exception {
        var context = context(user(status, deleted));
        var handler = new OAuth2SuccessHandler(jwt, sessions, users, accounts, exchanges, cookies, failure);
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, new TestingAuthenticationToken(context, null));
        // Refused like a blocked user's provider login: back to the front end with the reason.
        assertEquals("https://app.test/oauth2/success?error=oauth_login_failed&reason=blocked_user", response.getRedirectedUrl());
        verifyNoInteractions(jwt, sessions, exchanges, cookies);
    }

    @Test
    void activeLinkedAccountStillAuthenticates() {
        assertEquals("test-user", ((UserContext) serviceFor(user("active", "N")).loadUser(request())).getUserId());
    }

    @Test
    void unlinkedAccountStillRequiresSignup() {
        assertTrue(((UserContext) serviceFor(null).loadUser(request())).isSignupRequired());
    }

    @Test
    void activeSuccessCallbackStillIssuesCredentials() throws Exception {
        var handler = new OAuth2SuccessHandler(jwt, sessions, users, accounts, exchanges, cookies, failure);
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
            new TestingAuthenticationToken(context(user("active", "N")), null));
        verify(jwt).generateAccessToken("test-user", "user");
        verify(jwt).generateRefreshToken("test-user");
        verify(cookies).writeRefreshToken(any(), any(), isNull());
    }

    @ParameterizedTest
    @CsvSource({"dormant,false", "active,true"})
    void linkCallbackChecksTargetStatus(String status, boolean allowed) throws Exception {
        var target = user(status, "N");
        var claims = mock(io.jsonwebtoken.Claims.class);
        when(claims.get("userId")).thenReturn("test-user");
        when(claims.get("provider")).thenReturn("GOOGLE");
        when(jwt.validateOauthLinkToken("fixture-link")).thenReturn(claims);
        when(users.findByUserId("test-user")).thenReturn(Optional.of(target));
        var handler = new OAuth2SuccessHandler(jwt, sessions, users, accounts, exchanges, cookies, failure);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "frontendUrl", "https://app.test");
        var request = new MockHttpServletRequest();
        request.setParameter("state", "link." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("fixture-link\n/settings/account".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".fixture");
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(context(user("active", "N")), null));
        assertTrue(response.getRedirectedUrl().contains(allowed ? "linked=google" : "linkError=target_user_invalid"));
        verify(accounts, times(allowed ? 1 : 0)).save(any(SocialAccount.class));
        verifyNoInteractions(sessions, exchanges, cookies);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CustomOAuth2UserService serviceFor(User user) {
        var rest = mock(RestOperations.class);
        when(rest.exchange(any(RequestEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
            .thenReturn(ResponseEntity.ok(Map.of("sub", "provider-id", "email", "user@test.local", "name", "Test")));
        if (user != null) {
            var account = new SocialAccount();
            account.setUser(user);
            when(accounts.findByProviderAndProviderUserId("GOOGLE", "provider-id")).thenReturn(Optional.of(account));
        }
        var service = new CustomOAuth2UserService(accounts);
        service.setRestOperations(rest);
        return service;
    }

    private OAuth2UserRequest request() {
        var client = ClientRegistration.withRegistrationId("google").clientId("test-client")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://app.test/callback")
            .authorizationUri("https://oauth.test/authorize").tokenUri("https://oauth.test/token")
            .userInfoUri("https://oauth.test/userinfo").userNameAttributeName("sub").clientName("Test").build();
        return new OAuth2UserRequest(client, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
            "fixture-only", Instant.EPOCH, Instant.EPOCH.plusSeconds(60)));
    }

    private User user(String status, String deleted) {
        var user = new User();
        user.setUserId("test-user");
        user.setUserRole("user");
        user.setUserStatus(status);
        user.setDeleteYn(deleted);
        return user;
    }

    private UserContext context(User user) {
        return UserContext.registered(user, Map.of("sub", "provider-id"), "GOOGLE", "provider-id", "user@test.local", "Test");
    }

    private static OAuth2FailureHandler failureHandler() {
        var handler = new OAuth2FailureHandler();
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "redirectUri", "https://app.test/oauth2/success");
        return handler;
    }
}
