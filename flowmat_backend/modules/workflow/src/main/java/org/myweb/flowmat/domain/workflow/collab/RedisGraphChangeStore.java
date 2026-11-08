package org.myweb.flowmat.domain.workflow.collab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowGraphChangesResponse;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage.Type;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphEntityPayload;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class RedisGraphChangeStore {

    private static final String SEQ_KEY_PREFIX = "ws:graph:seq:";
    private static final String SEQ_INDEX_PREFIX = "ws:graph:by-seq:";
    private static final String TIME_INDEX_PREFIX = "ws:graph:by-time:";
    private static final String DATA_KEY_PREFIX = "ws:graph:data:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowCollabProperties collabProperties;

    public GraphChangeMessage append(
        Type type,
        String workflowId,
        String entityId,
        String userId,
        GraphEntityPayload payload
    ) {
        long timestamp = System.currentTimeMillis();
        long seq = nextSeq(workflowId);
        GraphChangeMessage message = GraphChangeMessage.of(
            seq,
            type,
            workflowId,
            entityId,
            userId,
            timestamp,
            payload
        );

        String member = Long.toString(seq);
        redisTemplate.opsForHash().put(dataKey(workflowId), member, writeMessage(message));
        redisTemplate.opsForZSet().add(seqIndexKey(workflowId), member, seq);
        redisTemplate.opsForZSet().add(timeIndexKey(workflowId), member, timestamp);
        refreshTtl(workflowId);
        pruneExpired(workflowId, timestamp - retentionMillis());
        return message;
    }

    public WorkflowGraphChangesResponse getChangesSince(String workflowId, long sinceSeq) {
        long currentSeq = getCurrentSeq(workflowId);
        Long oldestSeq = getOldestSeq(workflowId);
        boolean resetRequired = isResetRequired(sinceSeq, currentSeq, oldestSeq);

        if (resetRequired) {
            log.warn(
                "Graph change reset required for workflowId={}, sinceSeq={}, currentSeq={}, oldestAvailableSeq={}",
                workflowId,
                sinceSeq,
                currentSeq,
                oldestSeq
            );
            return new WorkflowGraphChangesResponse(currentSeq, true, List.of());
        }
        if (sinceSeq == currentSeq) {
            return new WorkflowGraphChangesResponse(currentSeq, false, List.of());
        }

        Set<String> members = redisTemplate.opsForZSet()
            .rangeByScore(seqIndexKey(workflowId), sinceSeq + 1, Double.POSITIVE_INFINITY);
        if (members == null || members.isEmpty()) {
            log.warn("Graph change index empty for workflowId={}, sinceSeq={}, currentSeq={}",
                workflowId, sinceSeq, currentSeq);
            return new WorkflowGraphChangesResponse(currentSeq, true, List.of());
        }

        List<Object> payloads = redisTemplate.opsForHash().multiGet(dataKey(workflowId), new ArrayList<>(members));
        if (payloads == null || payloads.size() != members.size() || payloads.stream().anyMatch(item -> item == null)) {
            log.warn("Graph change payload gap for workflowId={}, sinceSeq={}", workflowId, sinceSeq);
            return new WorkflowGraphChangesResponse(currentSeq, true, List.of());
        }
        List<GraphChangeMessage> changes = new ArrayList<>();
        for (Object payload : payloads) {
            changes.add(readMessage(payload.toString()));
        }
        changes.sort((left, right) -> Long.compare(left.seq(), right.seq()));
        long expectedSeq = sinceSeq + 1;
        for (GraphChangeMessage change : changes) {
            if (change.seq() != expectedSeq++) {
                log.warn("Graph change sequence gap for workflowId={}, sinceSeq={}", workflowId, sinceSeq);
                return new WorkflowGraphChangesResponse(currentSeq, true, List.of());
            }
        }
        if (expectedSeq - 1 != currentSeq) {
            log.warn("Graph change tail gap for workflowId={}, sinceSeq={}", workflowId, sinceSeq);
            return new WorkflowGraphChangesResponse(currentSeq, true, List.of());
        }
        return new WorkflowGraphChangesResponse(currentSeq, false, changes);
    }

    public long getCurrentSeq(String workflowId) {
        String current = redisTemplate.opsForValue().get(seqKey(workflowId));
        if (current == null || current.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(current);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private long nextSeq(String workflowId) {
        Long next = redisTemplate.opsForValue().increment(seqKey(workflowId));
        if (next == null) {
            throw new IllegalStateException("Failed to allocate graph sequence");
        }
        return next;
    }

    private Long getOldestSeq(String workflowId) {
        Set<String> members = redisTemplate.opsForZSet().range(seqIndexKey(workflowId), 0, 0);
        if (members == null || members.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(members.iterator().next());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isResetRequired(long sinceSeq, long currentSeq, Long oldestSeq) {
        if (sinceSeq > currentSeq) {
            return true;
        }
        if (currentSeq == sinceSeq) {
            return false;
        }
        if (oldestSeq == null) {
            return true;
        }
        return oldestSeq > sinceSeq + 1;
    }

    private void pruneExpired(String workflowId, long cutoffTimestamp) {
        Set<String> expiredMembers = redisTemplate.opsForZSet()
            .rangeByScore(timeIndexKey(workflowId), Double.NEGATIVE_INFINITY, cutoffTimestamp);
        if (expiredMembers == null || expiredMembers.isEmpty()) {
            return;
        }

        String[] members = expiredMembers.toArray(String[]::new);
        redisTemplate.opsForZSet().remove(timeIndexKey(workflowId), (Object[]) members);
        redisTemplate.opsForZSet().remove(seqIndexKey(workflowId), (Object[]) members);
        redisTemplate.opsForHash().delete(dataKey(workflowId), (Object[]) members);
    }

    private void refreshTtl(String workflowId) {
        Duration keyTtl = collabProperties.getGraph().getKeyTtl();
        redisTemplate.expire(seqKey(workflowId), keyTtl);
        redisTemplate.expire(seqIndexKey(workflowId), keyTtl);
        redisTemplate.expire(timeIndexKey(workflowId), keyTtl);
        redisTemplate.expire(dataKey(workflowId), keyTtl);
    }

    private long retentionMillis() {
        return collabProperties.getGraph().getRetention().toMillis();
    }

    private String writeMessage(GraphChangeMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize graph change", e);
        }
    }

    private GraphChangeMessage readMessage(String json) {
        try {
            return objectMapper.readValue(json, GraphChangeMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize graph change", e);
        }
    }

    private static String seqKey(String workflowId) {
        return SEQ_KEY_PREFIX + workflowId;
    }

    private static String seqIndexKey(String workflowId) {
        return SEQ_INDEX_PREFIX + workflowId;
    }

    private static String timeIndexKey(String workflowId) {
        return TIME_INDEX_PREFIX + workflowId;
    }

    private static String dataKey(String workflowId) {
        return DATA_KEY_PREFIX + workflowId;
    }
}
