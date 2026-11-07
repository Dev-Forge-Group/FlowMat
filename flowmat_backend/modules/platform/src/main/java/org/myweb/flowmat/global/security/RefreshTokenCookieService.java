package org.myweb.flowmat.global.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieService {

    private static final String COOKIE_NAME = "flowmat_rt";

    private final long refreshExpirySeconds;

    public RefreshTokenCookieService(
        @Value("${jwt.refresh-token-expiry-seconds:2592000}") long refreshExpirySeconds
    ) {
        this.refreshExpirySeconds = refreshExpirySeconds;
    }

    public void writeRefreshToken(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            clearRefreshToken(request, response);
            return;
        }

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, refreshToken)
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path("/api/auth")
            .maxAge(refreshExpirySeconds)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshToken(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path("/api/auth")
            .maxAge(0)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String resolveRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.trim());
    }
}
