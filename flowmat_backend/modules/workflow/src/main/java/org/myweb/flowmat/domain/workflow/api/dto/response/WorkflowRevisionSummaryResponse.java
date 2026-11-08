package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.time.OffsetDateTime;

public record WorkflowRevisionSummaryResponse(
    String workflowRevisionId,
    String workflowId,
    int revisionNo,
    String status,
    int schemaVersion,
    String publishedBy,
    OffsetDateTime publishedAt,
    String retiredBy,
    OffsetDateTime retiredAt
) {
}
