package org.myweb.flowmat.domain.bom.application.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;

/** A BOM revision with its lines, as the BOM API returns it and as other bounded contexts read it (ADR-036). */
public record BomView(
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
    List<BomLineView> lines,
    /** First day the revision is effective in the project's calendar; null is open (docs/domain/multi-level-bom.md). */
    LocalDate effectiveFrom,
    /** Last day it is effective; null is open. */
    LocalDate effectiveTo
) {

    /** Whether the revision is approved. */
    public boolean approved() {
        return BomStatus.APPROVED.code().equals(bomStatus);
    }
}
