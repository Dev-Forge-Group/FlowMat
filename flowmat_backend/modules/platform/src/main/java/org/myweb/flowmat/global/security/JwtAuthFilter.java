package org.myweb.flowmat.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/auth/signup",
        "/auth/login",
        "/auth/guest-token",
        "/auth/refresh",
        "/auth/logout",
        "/auth/check-nickname",
        "/auth/find-email",
        "/auth/reset-password/request",
        "/auth/reset-password/verify",
        "/auth/reset-password/confirm",
        "/auth/email/send-code",
        "/auth/email/verify-code",
        "/auth/dormant/reactivation/request",
        "/auth/dormant/reactivate",
        "/auth/face/login",
        "/auth/face/match",
        "/auth/face/select",
        "/auth/oauth2/exchange",
        "/auth/oauth2/kakao/complete",
        "/auth/oauth2/google/complete"
    );

    private final JwtProvider jwtProvider;

    public JwtAuthFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return PUBLIC_PATHS.contains(path) || path.startsWith("/ws") || path.startsWith("/oauth2");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            Authentication authentication = jwtProvider.getAuthentication(token);
            if (authentication instanceof org.springframework.security.authentication.AbstractAuthenticationToken auth) {
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
