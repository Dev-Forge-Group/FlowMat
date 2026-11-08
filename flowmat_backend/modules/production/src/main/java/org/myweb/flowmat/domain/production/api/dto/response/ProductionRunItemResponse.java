package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ProductionRunItemResponse(
    String productionRunItemId,
    String productionRunId,
    String processId,
    String processIoId,
    String inventoryId,
    String itemId,
    String direction,
    BigDecimal plannedQty,
    BigDecimal actualQty,
    String unit,
    /** "manual", "bom" (planned from the BOM snapshot at run start) or "correction" (added by a finished-run correction). */
    String quantitySource,
    BigDecimal conversionRate,
    /** LOT consumed or produced; set from the chosen stock record. */
    String lotId,
    boolean cancelled,
    String cancelledBy,
    OffsetDateTime cancelledAt,
    String cancelReason,
    /** The correction that added this recording. */
    String productionRunCorrectionId,
    /** The correction that voided this recording; null when it was cancelled on the open run. */
    String cancelledByCorrectionId,
    /** For a planned BOM line: the phantom sub-assembly it was expanded through (docs/domain/multi-level-bom.md P2). */
    String viaItemId
) {
}
