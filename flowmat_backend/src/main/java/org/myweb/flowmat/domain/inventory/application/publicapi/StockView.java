package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What another bounded context may read about a stock record: its item, LOT and place, what it holds (on hand, reserved,
 * and available = on hand less reserved), its status (available or quarantined) and when it was created.
 */
public record StockView(
    String inventoryId,
    String projectId,
    String itemId,
    String lotId,
    String location,
    BigDecimal quantity,
    BigDecimal reservedQuantity,
    BigDecimal availableQuantity,
    String inventoryStatus,
    OffsetDateTime createdAt
) {
}
