package org.myweb.flowmat.global.websocket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.AuthUser;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더에서 토큰을 추출해 Principal 을 설정한다.
 * JwtProvider가 access token의 서명, 만료, token type을 검증하고 workflow별 읽기/쓰기 권한을 확인한다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern WORKFLOW_DESTINATION =
        Pattern.compile("^/(?:topic|app)/workflow/([^/]+)/(?:presence|node-move|graph)$");

    private final JwtProvider jwtProvider;
    private final WorkflowAccess workflowAccess;

    public StompAuthChannelInterceptor(JwtProvider jwtProvider, WorkflowAccess workflowAccess) {
        this.jwtProvider = jwtProvider;
        this.workflowAccess = workflowAccess;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authorization = accessor.getFirstNativeHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing STOMP Authorization header.");
            }

            String token = authorization.substring(7).trim();
            jwtProvider.validate(token);
            if (!jwtProvider.isAccessToken(token)) {
                throw new BusinessException(ErrorCode.TOKEN_TYPE_INVALID);
            }

            String userId = jwtProvider.resolveUserId(token);
            if (userId == null || userId.isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Unable to resolve authenticated STOMP user.");
            }

            AuthUser authUser = new AuthUser(userId);
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(authUser, null, authUser.getAuthorities());
            accessor.setUser(authentication);
        }

        if (StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (accessor.getUser() == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Unauthenticated STOMP frame.");
            }
            String destination = accessor.getDestination();
            boolean allowedPrefix = StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                ? destination != null && destination.startsWith("/topic/")
                : destination != null && destination.startsWith("/app/");
            Matcher matcher = destination == null ? null : WORKFLOW_DESTINATION.matcher(destination);
            if (!allowedPrefix || matcher == null || !matcher.matches()
                || matcher.group(1).contains("*") || matcher.group(1).contains("?")) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "STOMP destination is not allowed.");
            }
            String workflowId = matcher.group(1);
            String userId;
            if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof AuthUser authUser) {
                userId = authUser.getUserId();
            } else {
                userId = accessor.getUser().getName();
            }
            if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                workflowAccess.requireRead(workflowId, userId);
            } else {
                workflowAccess.requireWrite(workflowId, userId);
            }
        }

        return message;
    }
}
