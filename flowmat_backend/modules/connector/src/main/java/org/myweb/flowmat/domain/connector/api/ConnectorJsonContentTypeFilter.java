package org.myweb.flowmat.domain.connector.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Reject form bodies before security/logging filters can parse servlet parameters. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class ConnectorJsonContentTypeFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) && !"PUT".equals(request.getMethod())
            && !"PATCH".equals(request.getMethod())) return true;
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.matches("/projects/[^/]+/json-mapping/preview/?")
            && !path.matches("/projects/[^/]+/http-profiles(?:/.*)?")
            && !path.matches("/processes/[^/]+/execution-binding/?")
            && !path.matches("/flow-runs/[^/]+/steps/[^/]+/(?:execute|http-executions(?:/.*)?)/?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean bodylessApply = request.getContentType() == null && request.getContentLengthLong() <= 0
            && request.getHeader("Transfer-Encoding") == null
            && path.matches("/flow-runs/[^/]+/steps/[^/]+/http-executions/[^/]+/apply/?");
        if (isJson(request.getContentType()) || bodylessApply) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        mapper.writeValue(response.getOutputStream(), ApiResponse.error("Content-Type must be application/json."));
    }

    private boolean isJson(String value) {
        if (value == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(value);
            return "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
