package org.myweb.flowmat.domain.quality.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** An inspection with the names needed to show it. {@code lotStatus} is the LOT's status now, not at inspection time. */
public record QualityInspectionResponse(
    String inspectionId,
    String projectId,
    String productionRunId,
    String runNumber,
    String itemId,
    String itemCode,
    String itemName,
    String lotId,
    String lotNo,
    String lotStatus,
    String inspectionType,
    String resultStatus,
    BigDecimal measuredValue,
    BigDecimal standardMin,
    BigDecimal standardMax,
    String unit,
    String note,
    String inspectedBy,
    OffsetDateTime inspectedAt,
    /** The inspection standard followed, if any (docs/domain/inspection-standard.md). */
    String standardId
) {
}
