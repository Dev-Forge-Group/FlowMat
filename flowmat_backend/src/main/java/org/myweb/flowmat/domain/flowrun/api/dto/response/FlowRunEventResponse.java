package org.myweb.flowmat.domain.flowrun.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record FlowRunEventResponse(
    String eventId,
    String flowRunId,
    String stepId,
    String eventType,
    JsonNode payload,
    String requestId,
    OffsetDateTime occurredAt,
    String actorType,
    String actorId
) {
    /** JSON text preserves numbers for clients that parse ordinary JSON numbers as doubles. */
    @JsonProperty("payloadJson")
    public String payloadJson() { return payload == null ? null : payload.toString(); }
}
