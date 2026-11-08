package org.myweb.flowmat.domain.workflow.collab;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.myweb.flowmat.domain.workflow.collab.dto.AnnotationPresencePayload;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

/**
 * CURSOR_MOVED / NODE_EDITING 같은 presence 메시지를 relay 한다.
 * 클라이언트가 보낸 userId/workflowId/timestamp 를 서버가 재설정한다.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PresenceController {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowSessionRegistry sessionRegistry;
    private final WorkflowAccessService workflowAccess;

    @MessageMapping("/workflow/{workflowId}/presence")
    public void relay(
        @DestinationVariable("workflowId") String workflowId,
        @Payload PresenceMessage message,
        Principal principal,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        String userId = resolveUserId(principal);
        if (message == null || message.type() == null || userId == null || workflowId == null || workflowId.isBlank()) {
            log.warn("Dropping malformed presence message for workflowId={}", workflowId);
            return;
        }
        workflowAccess.requireWorkflowReadAccess(workflowId, userId);
        if (!isValid(message)) {
            log.warn("Dropping invalid presence message for workflowId={}, userId={}", workflowId, userId);
            return;
        }
        String sessionId = headerAccessor == null ? null : headerAccessor.getSessionId();
        if (sessionId != null && userId != null) {
            if (message.type() == PresenceMessage.Type.LEAVE) {
                sessionRegistry.remove(sessionId);
            } else if (message.type() != PresenceMessage.Type.ANNOTATION_DRAWING) {
                sessionRegistry.touch(sessionId, userId, workflowId, message);
            } else {
                sessionRegistry.touch(sessionId, userId, workflowId, message.clientId());
            }
        }

        if (message.type() == PresenceMessage.Type.HEARTBEAT) {
            return;
        }

        String destination = "/topic/workflow/" + workflowId + "/presence";
        messagingTemplate.convertAndSend(destination, message.withServerValues(userId, workflowId));
    }

    private boolean isValid(PresenceMessage message) {
        if (!hasRequiredLength(message.clientId(), 128)) {
            return false;
        }
        if (message.cursorX() != null && !isCoordinate(message.cursorX())) {
            return false;
        }
        if (message.cursorY() != null && !isCoordinate(message.cursorY())) {
            return false;
        }
        if (message.editingProcessId() != null && !hasRequiredLength(message.editingProcessId(), 128)) {
            return false;
        }
        if (message.annotation() != null && !isValidAnnotation(message.annotation())) {
            return false;
        }
        return switch (message.type()) {
            case JOIN, LEAVE, HEARTBEAT -> message.cursorX() == null
                && message.cursorY() == null
                && message.editingProcessId() == null;
            case CURSOR_MOVED -> message.cursorX() != null && message.cursorY() != null;
            // No process means the sender stopped editing (it deselected); a process id was checked above.
            case NODE_EDITING -> true;
            case ANNOTATION_DRAWING -> message.annotation() != null;
        };
    }

    private boolean isCoordinate(double value) {
        return Double.isFinite(value) && value >= -1_000_000d && value <= 1_000_000d;
    }

    private boolean hasRequiredLength(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private boolean isValidAnnotation(AnnotationPresencePayload annotation) {
        if (annotation.annotationType() == null
            || !(annotation.annotationType().equals("freehand")
                || annotation.annotationType().equals("shape")
                || annotation.annotationType().equals("text"))
            || annotation.inProgress() == null
            || annotation.points() == null
            || annotation.points().size() > 4096) {
            return false;
        }
        for (var point : annotation.points()) {
            if (point == null || point.size() != 2 || point.get(0) == null || point.get(1) == null
                || !isCoordinate(point.get(0)) || !isCoordinate(point.get(1))) {
                return false;
            }
        }
        return annotation.toString().length() <= 16_384;
    }

    private String resolveUserId(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
            && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        return null;
    }
}
