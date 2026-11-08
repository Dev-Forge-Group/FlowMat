package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RunStateSnapshotCreateRequest(
    @NotBlank String productionRunId,
    String snapshotName,
    String snapshotType,
    @NotBlank String snapshotData,
    String note,
    String createdBy
) {
}
