package org.myweb.flowmat.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.myweb.flowmat.global.security.AuthUser;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = normalize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());
        putIfPresent("projectId", firstNonBlank(request.getHeader("X-Project-Id"), request.getParameter("projectId")));
        putIfPresent("workflowId", firstNonBlank(request.getHeader("X-Workflow-Id"), request.getParameter("workflowId")));
        putIfPresent("runId", firstNonBlank(request.getHeader("X-Run-Id"), request.getParameter("runId")));
        putIfPresent("userId", resolveUserId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private void putIfPresent(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private String resolveUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthUser authUser && authUser.getUserId() != null) {
            return authUser.getUserId();
        }
        return normalize(authentication.getName());
    }

    private String firstNonBlank(String first, String second) {
        String firstValue = normalize(first);
        if (firstValue != null) {
            return firstValue;
        }
        return normalize(second);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
