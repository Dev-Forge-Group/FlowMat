package org.myweb.flowmat.domain.flowrun.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** Public evidence excludes provider response, credential aliases and internal claim tokens. */
public record FlowRunHttpExecutionResponse(String executionId, String stepId, String attemptId, String requestId,
    String transportStatus, String applyStatus, Integer httpStatus, String errorCode, JsonNode output,
    OffsetDateTime startedAt, OffsetDateTime completedAt) {
    @JsonProperty("outputJson")
    public String outputJson() { return output == null ? null : output.toString(); }
    @Override public String toString() { return "FlowRunHttpExecutionResponse[executionId=" + executionId + ", transportStatus=" + transportStatus + "]"; }
}
