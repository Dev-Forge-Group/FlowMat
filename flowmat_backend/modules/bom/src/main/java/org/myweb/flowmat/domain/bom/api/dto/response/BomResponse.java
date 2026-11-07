package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record BomResponse(
    String bomId,
    String projectId,
    String targetItemId,
    String bomName,
    Integer bomVersion,
    BigDecimal baseQuantity,
    String baseUnit,
    String bomStatus,
    String approvedBy,
    OffsetDateTime approvedAt,
    String note,
    List<BomLineResponse> lines,
    /** First day the revision is effective in the project's calendar; null is open (docs/domain/multi-level-bom.md). */
    LocalDate effectiveFrom,
    /** Last day it is effective; null is open. */
    LocalDate effectiveTo
) {
}
