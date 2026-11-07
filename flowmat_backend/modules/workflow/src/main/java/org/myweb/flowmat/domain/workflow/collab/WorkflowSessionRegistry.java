package org.myweb.flowmat.domain.workflow.collab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.collab.dto.PresenceMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowSessionRegistry {

    private static final String KEY_PREFIX = "ws:session:";
    private static final String ACTIVE_SESSIONS_KEY = "ws:sessions:active";
    private static final String FIELD_USER = "userId";
    private static final String FIELD_WORKFLOW = "workflowId";
    private static final String FIELD_CLIENT = "clientId";
    private static final String FIELD_CURSOR_X = "cursorX";
    private static final String FIELD_CURSOR_Y = "cursorY";
    private static final String FIELD_EDITING_PROCESS_ID = "editingProcessId";
    private static final String FIELD_LAST_SEEN = "lastSeenAt";
    private static final long SESSION_TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;

    public void register(String sessionId, String userId, String workflowId) {
        register(sessionId, userId, workflowId, null);
    }

    public void register(String sessionId, String userId, String workflowId, String clientId) {
        writeSession(sessionId, userId, workflowId, clientId, System.currentTimeMillis());
    }

    public void touch(String sessionId, String userId, String workflowId, String clientId) {
        writeSession(sessionId, userId, workflowId, clientId, System.currentTimeMillis());
    }

    public void touch(String sessionId, String userId, String workflowId, PresenceMessage message) {
        WorkflowSessionRecord current = resolveSession(sessionId);
        writeSession(
            sessionId,
            userId,
            workflowId,
            message.clientId(),
            message.cursorX() != null ? message.cursorX() : current != null ? current.cursorX() : null,
            message.cursorY() != null ? message.cursorY() : current != null ? current.cursorY() : null,
            message.type() == PresenceMessage.Type.NODE_EDITING
                ? message.editingProcessId()
                : current != null ? current.editingProcessId() : null,
            System.currentTimeMillis()
        );
    }

    public WorkflowSessionRecord resolveSession(String sessionId) {
        String key = key(sessionId);
        Object userId = redisTemplate.opsForHash().get(key, FIELD_USER);
        Object workflowId = redisTemplate.opsForHash().get(key, FIELD_WORKFLOW);
        Object clientId = redisTemplate.opsForHash().get(key, FIELD_CLIENT);
        Object cursorX = redisTemplate.opsForHash().get(key, FIELD_CURSOR_X);
        Object cursorY = redisTemplate.opsForHash().get(key, FIELD_CURSOR_Y);
        Object editingProcessId = redisTemplate.opsForHash().get(key, FIELD_EDITING_PROCESS_ID);
        Object lastSeenAt = redisTemplate.opsForHash().get(key, FIELD_LAST_SEEN);
        if (userId == null && workflowId == null && clientId == null && lastSeenAt == null
            && cursorX == null && cursorY == null && editingProcessId == null) {
            return null;
        }
        return new WorkflowSessionRecord(
            sessionId,
            userId != null ? userId.toString() : null,
            workflowId != null ? workflowId.toString() : null,
            clientId != null ? clientId.toString() : null,
            parseDouble(cursorX),
            parseDouble(cursorY),
            editingProcessId != null ? editingProcessId.toString() : null,
            parseLong(lastSeenAt)
        );
    }

    public String resolveUserId(String sessionId) {
        WorkflowSessionRecord session = resolveSession(sessionId);
        return session != null ? session.userId() : null;
    }

    public String resolveWorkflowId(String sessionId) {
        WorkflowSessionRecord session = resolveSession(sessionId);
        return session != null ? session.workflowId() : null;
    }

    public List<WorkflowSessionRecord> findStaleSessions(long cutoffMillis) {
        return listSessions(null, cutoffMillis, true);
    }

    public List<WorkflowSessionRecord> listWorkflowSessions(String workflowId) {
        return listSessions(workflowId, 0L, false);
    }

    private List<WorkflowSessionRecord> listSessions(String workflowId, long cutoffMillis, boolean staleOnly) {
        Set<String> sessionIds = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_KEY);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }

        List<WorkflowSessionRecord> staleSessions = new ArrayList<>();
        for (String sessionId : sessionIds) {
            WorkflowSessionRecord session = resolveSession(sessionId);
            if (session == null) {
                redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_KEY, sessionId);
                continue;
            }
            if (workflowId != null && !workflowId.equals(session.workflowId())) {
                continue;
            }
            if (!staleOnly || session.lastSeenAt() <= cutoffMillis) {
                staleSessions.add(session);
            }
        }
        return staleSessions;
    }

    public WorkflowSessionRecord removeIfStale(String sessionId, long cutoffMillis) {
        WorkflowSessionRecord session = resolveSession(sessionId);
        if (session == null || session.lastSeenAt() > cutoffMillis) {
            return null;
        }
        remove(sessionId);
        return session;
    }

    public void remove(String sessionId) {
        redisTemplate.delete(key(sessionId));
        redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_KEY, sessionId);
    }

    private void writeSession(String sessionId, String userId, String workflowId, String clientId, long lastSeenAt) {
        writeSession(sessionId, userId, workflowId, clientId, null, null, null, lastSeenAt);
    }

    private void writeSession(
        String sessionId,
        String userId,
        String workflowId,
        String clientId,
        Double cursorX,
        Double cursorY,
        String editingProcessId,
        long lastSeenAt
    ) {
        String key = key(sessionId);
        redisTemplate.opsForHash().put(key, FIELD_USER, userId);
        redisTemplate.opsForHash().put(key, FIELD_WORKFLOW, workflowId);
        if (clientId != null && !clientId.isBlank()) {
            redisTemplate.opsForHash().put(key, FIELD_CLIENT, clientId);
        }
        if (cursorX != null) {
            redisTemplate.opsForHash().put(key, FIELD_CURSOR_X, Double.toString(cursorX));
        }
        if (cursorY != null) {
            redisTemplate.opsForHash().put(key, FIELD_CURSOR_Y, Double.toString(cursorY));
        }
        if (editingProcessId != null) {
            redisTemplate.opsForHash().put(key, FIELD_EDITING_PROCESS_ID, editingProcessId);
        } else {
            redisTemplate.opsForHash().delete(key, FIELD_EDITING_PROCESS_ID);
        }
        redisTemplate.opsForHash().put(key, FIELD_LAST_SEEN, Long.toString(lastSeenAt));
        redisTemplate.expire(key, SESSION_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.opsForSet().add(ACTIVE_SESSIONS_KEY, sessionId);
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
