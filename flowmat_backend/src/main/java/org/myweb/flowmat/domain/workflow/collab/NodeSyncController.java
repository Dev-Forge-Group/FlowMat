package org.myweb.flowmat.domain.workflow.collab;

import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.application.WorkflowAccessService;
import org.myweb.flowmat.domain.workflow.collab.dto.NodeMoveMessage;
import org.myweb.flowmat.global.security.AuthUser;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

/**
 * 노드 드래그 "도중" 위치를 같은 workflow 구독자들에게 순수 릴레이한다.
 * DB 에 쓰지 않는다 — 드래그 종료 시 최종 위치 저장은 REST PUT /api/processes/{id} 가 담당한다.
 */
@Controller
@RequiredArgsConstructor
public class NodeSyncController {

    private final WorkflowAccessService workflowAccess;

    @MessageMapping("/workflow/{workflowId}/node-move")
    @SendTo("/topic/workflow/{workflowId}/node-move")
    public NodeMoveMessage relay(
        @DestinationVariable("workflowId") String workflowId,
        @Payload NodeMoveMessage message,
        Principal principal
    ) {
        String userId = resolveUserId(principal);
        workflowAccess.requireWorkflowWriteAccess(workflowId, userId);
        if (message == null || message.processId() == null || message.processId().isBlank()
            || message.processId().length() > 128
            || !Double.isFinite(message.x()) || !Double.isFinite(message.y())
            || message.x() < -1_000_000d || message.x() > 1_000_000d
            || message.y() < -1_000_000d || message.y() > 1_000_000d
            || message.clientId() == null || message.clientId().isBlank() || message.clientId().length() > 128) {
            throw new IllegalArgumentException("Invalid node move payload");
        }
        return message.withServerValues(userId, workflowId);
    }

    private String resolveUserId(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
            && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        return null;
    }
}
