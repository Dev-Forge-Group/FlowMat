package org.myweb.flowmat.domain.flowrun.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record FlowRunResponse(
    String flowRunId,
    String projectId,
    String workflowId,
    String workflowRevisionId,
    String productionRunId,
    String runType,
    String executionMode,
    String status,
    JsonNode inputPayload,
    JsonNode outputPayload,
    OffsetDateTime startedAt,
    OffsetDateTime endedAt,
    String requestedBy
) {
    /** JSON text preserves numbers for clients that parse ordinary JSON numbers as doubles. */
    @JsonProperty("inputPayloadJson")
    public String inputPayloadJson() { return inputPayload == null ? null : inputPayload.toString(); }

    @JsonProperty("outputPayloadJson")
    public String outputPayloadJson() { return outputPayload == null ? null : outputPayload.toString(); }
}
