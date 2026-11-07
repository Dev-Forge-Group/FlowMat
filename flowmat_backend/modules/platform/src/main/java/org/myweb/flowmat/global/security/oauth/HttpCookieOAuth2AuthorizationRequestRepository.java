package org.myweb.flowmat.global.security.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
public class HttpCookieOAuth2AuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int EXPIRE_SECONDS = 180;
    private static final String SIGNATURE_SEPARATOR = ".";

    private final ObjectMapper objectMapper;
    private final SecretKeySpec signingKey;

    public HttpCookieOAuth2AuthorizationRequestRepository(
        ObjectMapper objectMapper,
        String signingSecret
    ) {
        this.objectMapper = objectMapper;
        this.signingKey = new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = getCookie(request, COOKIE_NAME);
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        try {
            return deserialize(cookie.getValue());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
        OAuth2AuthorizationRequest authorizationRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            removeCookie(request, response, COOKIE_NAME);
            return;
        }
        addCookie(response, COOKIE_NAME, serialize(authorizationRequest), EXPIRE_SECONDS, isSecureRequest(request));
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        removeCookie(request, response, COOKIE_NAME);
        return loaded;
    }

    private Cookie getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void removeCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        if (getCookie(request, name) == null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(name, "")
            .path("/")
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .maxAge(0)
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

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        AuthorizationRequestCookiePayload payload = AuthorizationRequestCookiePayload.from(authorizationRequest);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return encoded + SIGNATURE_SEPARATOR + sign(encoded);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OAuth2 authorization request.", e);
        }
    }

    private OAuth2AuthorizationRequest deserialize(String value) {
        try {
            int separatorIndex = value.lastIndexOf(SIGNATURE_SEPARATOR);
            if (separatorIndex <= 0 || separatorIndex == value.length() - 1) {
                throw new IllegalStateException("Missing OAuth2 authorization request signature.");
            }
            String encodedPayload = value.substring(0, separatorIndex);
            String providedSignature = value.substring(separatorIndex + 1);
            String expectedSignature = sign(encodedPayload);
            if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new IllegalStateException("OAuth2 authorization request signature mismatch.");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(encodedPayload.getBytes(StandardCharsets.UTF_8));
            AuthorizationRequestCookiePayload payload =
                objectMapper.readValue(bytes, AuthorizationRequestCookiePayload.class);
            return payload.toAuthorizationRequest();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize OAuth2 authorization request.", e);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign OAuth2 authorization request.", e);
        }
    }

    private record AuthorizationRequestCookiePayload(
        String authorizationUri,
        String clientId,
        String redirectUri,
        String state,
        String authorizationRequestUri,
        Map<String, Object> additionalParameters,
        Map<String, Object> attributes,
        String registrationId,
        java.util.Set<String> scopes
    ) {
        static AuthorizationRequestCookiePayload from(OAuth2AuthorizationRequest request) {
            String registrationId = null;
            Object rawRegistrationId = request.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
            if (rawRegistrationId != null) {
                registrationId = String.valueOf(rawRegistrationId);
            }
            return new AuthorizationRequestCookiePayload(
                request.getAuthorizationUri(),
                request.getClientId(),
                request.getRedirectUri(),
                request.getState(),
                request.getAuthorizationRequestUri(),
                request.getAdditionalParameters() == null ? Map.of() : new LinkedHashMap<>(request.getAdditionalParameters()),
                request.getAttributes() == null ? Map.of() : new LinkedHashMap<>(request.getAttributes()),
                registrationId,
                request.getScopes()
            );
        }

        OAuth2AuthorizationRequest toAuthorizationRequest() {
            Map<String, Object> requestAttributes = new LinkedHashMap<>();
            if (attributes != null) {
                requestAttributes.putAll(attributes);
            }
            if (registrationId != null && !registrationId.isBlank()) {
                requestAttributes.put(OAuth2ParameterNames.REGISTRATION_ID, registrationId);
            }
            return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(authorizationUri)
                .clientId(clientId)
                .redirectUri(redirectUri)
                .scopes(scopes == null ? java.util.Set.of() : scopes)
                .state(state)
                .additionalParameters(additionalParameters == null ? Map.of() : additionalParameters)
                .attributes(requestAttributes)
                .authorizationRequestUri(authorizationRequestUri)
                .build();
        }
    }
}
