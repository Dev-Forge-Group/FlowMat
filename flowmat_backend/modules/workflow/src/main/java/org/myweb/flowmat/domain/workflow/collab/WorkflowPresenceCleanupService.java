package org.myweb.flowmat.domain.workflow.collab;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowPresenceCleanupService {

    private final WorkflowSessionRegistry sessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowCollabProperties collabProperties;

    @Scheduled(fixedDelayString = "${app.workflow-collab.presence.cleanup-interval:PT15S}")
    public void cleanupStaleSessions() {
        long cutoffMillis = System.currentTimeMillis() - collabProperties.getPresence().getHeartbeatTimeout().toMillis();
        List<WorkflowSessionRecord> staleSessions = sessionRegistry.findStaleSessions(cutoffMillis);
        int removedCount = 0;
        for (WorkflowSessionRecord staleSession : staleSessions) {
            WorkflowSessionRecord removed = sessionRegistry.removeIfStale(staleSession.sessionId(), cutoffMillis);
            if (removed == null || removed.userId() == null || removed.workflowId() == null) {
                continue;
            }
            removedCount++;

            messagingTemplate.convertAndSend(
                "/topic/workflow/" + removed.workflowId() + "/presence",
                PresenceMessage.leave(removed.userId(), null, removed.workflowId())
            );
        }
        if (removedCount > 0) {
            log.info("Removed {} stale workflow presence sessions.", removedCount);
        }
    }
}
