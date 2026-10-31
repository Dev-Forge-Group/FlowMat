package org.myweb.flowmat.domain.flowrun.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** A bounded review page without request data, provider output or internal transmission claims. */
public record FlowRunHttpAttentionPageResponse(List<Item> items, String nextCursor) {
    public record Item(String executionId, String flowRunId, String workflowId, String runStatus,
        String stepId, String nodeId, String stepStatus, String attemptId, String operationId,
        String transportStatus, String applyStatus, Integer httpStatus, String errorCode,
        OffsetDateTime startedAt, OffsetDateTime completedAt) { }
}
