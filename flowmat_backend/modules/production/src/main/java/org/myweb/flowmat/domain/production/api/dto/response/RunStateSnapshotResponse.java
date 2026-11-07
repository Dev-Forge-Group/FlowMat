package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;

public record RunStateSnapshotResponse(
    String runStateSnapshotId,
    String productionRunId,
    String snapshotName,
    String snapshotType,
    String snapshotData,
    String note,
    String createdBy,
    OffsetDateTime createdAt
) {
}
