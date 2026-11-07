package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record InventoryResponse(
    String inventoryId,
    String projectId,
    String itemId,
    BigDecimal quantity,
    BigDecimal reservedQuantity,
    BigDecimal availableQuantity,
    String inventoryStatus,
    String location,
    BigDecimal minThreshold,
    BigDecimal maxThreshold,
    /** "low" when available &lt; minThreshold, "over" when quantity &gt; maxThreshold, otherwise "ok". */
    String stockLevel,
    Long version,
    String lotId,
    String lotNo,
    /** When a stock count last covered the record; null when never counted. */
    OffsetDateTime lastCheckedAt,
    String lastCheckedBy
) {
}
