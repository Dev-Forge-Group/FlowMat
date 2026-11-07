package org.myweb.flowmat.domain.quality.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A logged defect with the names needed to show it; {@code unit} is the item's unit. */
public record DefectResponse(
    String defectLogId,
    String projectId,
    String inspectionId,
    String productionRunId,
    String runNumber,
    String itemId,
    String itemCode,
    String itemName,
    String lotId,
    String lotNo,
    String defectType,
    BigDecimal quantity,
    String unit,
    String severity,
    String reason,
    boolean resolved,
    String actionTaken,
    String loggedBy,
    OffsetDateTime loggedAt,
    String resolvedBy,
    OffsetDateTime resolvedAt
) {
}
