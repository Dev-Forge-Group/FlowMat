package org.myweb.flowmat.domain.flowrun.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record FlowRunStepResponse(
    String stepId,
    String flowRunId,
    String nodeId,
    String sourceConnectionId,
    String sourceStepId,
    String status,
    Integer sequenceNo,
    OffsetDateTime scheduledAt,
    OffsetDateTime startedAt,
    OffsetDateTime endedAt,
    JsonNode inputSnapshot,
    JsonNode outputSnapshot,
    String errorCode,
    String errorMessage,
    /** A Join step's connections that can still bring something (ADR-007), 0 once passed; null for other steps and answers. */
    Integer joinWaiting
) {
    /** JSON text preserves numbers for clients that parse ordinary JSON numbers as doubles. */
    @JsonProperty("inputSnapshotJson")
    public String inputSnapshotJson() { return inputSnapshot == null ? null : inputSnapshot.toString(); }

    @JsonProperty("outputSnapshotJson")
    public String outputSnapshotJson() { return outputSnapshot == null ? null : outputSnapshot.toString(); }
}
