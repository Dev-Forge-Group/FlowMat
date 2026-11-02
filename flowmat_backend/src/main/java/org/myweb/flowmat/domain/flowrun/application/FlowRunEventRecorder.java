package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunEvent;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.global.id.IdGenerator;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FlowRunEventRecorder {
    private final FlowRunEventRepository repository;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    /** The time the last event got; see {@link #nextOccurredAt()}. */
    private final AtomicReference<OffsetDateTime> lastOccurredAt = new AtomicReference<>();

    public void record(String runId, String stepId, String type, JsonNode payload, String actorId) {
        FlowRunEvent event = new FlowRunEvent();
        event.setEventId(idGenerator.generate());
        event.setFlowRunId(runId);
        event.setStepId(stepId);
        event.setEventType(type);
        try {
            event.setPayloadJson(objectMapper.writeValueAsString(payload == null
                ? objectMapper.createObjectNode() : payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Flow run event payload could not be serialized.", exception);
        }
        event.setRequestId(MDC.get("requestId"));
        event.setOccurredAt(nextOccurredAt());
        boolean userActor = actorId != null && !actorId.isBlank();
        event.setActorType(userActor ? "user" : "system");
        event.setActorId(userActor ? actorId : "system");
        repository.save(event);
    }

    /**
     * Now in microseconds (what Postgres keeps), but always after the last event this recorder timed (flow-run F19). The ledger
     * sorts by time and then by a random id, so two events of one request that read the same clock tick, such as
     * {@code step_completed} and the {@code connection_filtered} after it, would otherwise come back in either order. Windows
     * clock ticks are coarse enough for that.
     */
    OffsetDateTime nextOccurredAt() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return lastOccurredAt.accumulateAndGet(now, (last, candidate) ->
            last == null || candidate.isAfter(last) ? candidate : last.plus(1, ChronoUnit.MICROS));
    }
}
