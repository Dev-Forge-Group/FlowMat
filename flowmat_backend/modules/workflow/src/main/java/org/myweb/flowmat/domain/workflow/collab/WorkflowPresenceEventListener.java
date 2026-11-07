package org.myweb.flowmat.domain.workflow.collab;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Presence(접속자) 이벤트 처리.
 * 클라이언트가 "/topic/workflow/{id}/presence" 를 구독하면 JOIN 을,
 * 연결이 끊기면 LEAVE 를 같은 destination 으로 브로드캐스트한다.
 */
@Component
public class WorkflowPresenceEventListener {

    private static final Pattern PRESENCE_DESTINATION =
        Pattern.compile("^/topic/workflow/([^/]+)/presence$");

    private final WorkflowSessionRegistry sessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    public WorkflowPresenceEventListener(
        WorkflowSessionRegistry sessionRegistry,
        SimpMessagingTemplate messagingTemplate
    ) {
        this.sessionRegistry = sessionRegistry;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) return;

        Matcher matcher = PRESENCE_DESTINATION.matcher(destination);
        if (!matcher.matches()) return;

        String workflowId = matcher.group(1);
        String sessionId = accessor.getSessionId();
        String userId = resolveUserId(accessor.getUser());
        if (sessionId == null || userId == null) return;

        sessionRegistry.register(sessionId, userId, workflowId);
        // clientId is null for server-generated events — only client-sent messages carry it.
        messagingTemplate.convertAndSend(destination, PresenceMessage.join(userId, null, workflowId));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId == null) return;

        String userId = sessionRegistry.resolveUserId(sessionId);
        String workflowId = sessionRegistry.resolveWorkflowId(sessionId);
        sessionRegistry.remove(sessionId);

        if (userId == null || workflowId == null) return;

        String destination = "/topic/workflow/" + workflowId + "/presence";
        messagingTemplate.convertAndSend(destination, PresenceMessage.leave(userId, null, workflowId));
    }

    private String resolveUserId(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
            && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        return null;
    }
}
