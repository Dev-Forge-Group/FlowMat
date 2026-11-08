package org.myweb.flowmat.global.security.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    private static final String LINK_STATE_PREFIX = "link.";

    @Value("${app.oauth2.redirect-uri:}")
    private String redirectUri;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException, ServletException {
        String reason = exception == null || exception.getMessage() == null
            ? "oauth_login_failed"
            : exception.getMessage();

        LinkState linkState = parseLinkState(request.getParameter("state"));
        if (linkState != null) {
            response.sendRedirect(buildFrontendUrl(request, appendQuery(sanitizeReturnPath(linkState.returnTo()), "linkError", "oauth_login_failed")));
            return;
        }

        String resolvedRedirectUri = resolveRedirectUri(request);
        response.sendRedirect(
            resolvedRedirectUri
                + "?error=oauth_login_failed&reason="
                + URLEncoder.encode(reason, StandardCharsets.UTF_8)
        );
    }

    private String sanitizeReturnPath(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/settings/account";
        }
        return returnTo.trim();
    }

    private String appendQuery(String path, String key, String value) {
        return path + (path.contains("?") ? "&" : "?") + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String buildFrontendUrl(HttpServletRequest request, String path) {
        String base = frontendUrl == null || frontendUrl.isBlank() ? resolveRequestOrigin(request) : frontendUrl;
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
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

    private record LinkState(String linkToken, String returnTo) {
    }
}
