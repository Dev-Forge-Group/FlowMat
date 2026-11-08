package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record WorkflowRevisionResponse(
    String workflowRevisionId,
    String workflowId,
    int revisionNo,
    String status,
    int schemaVersion,
    JsonNode snapshot,
    String publishedBy,
    OffsetDateTime publishedAt,
    String retiredBy,
    OffsetDateTime retiredAt
) {
}
