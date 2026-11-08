package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * An item's safety stock at one place (docs/domain/stock-alert.md PS3). {@code unit} is the item's unit;
 * {@code updatedBy} and {@code updatedAt} are of the last change, or of the setting when it never changed.
 */
public record PlaceSafetyStockResponse(
    String placeSafetyStockId,
    String itemId,
    String itemCode,
    String itemName,
    String unit,
    String locationId,
    String locationCode,
    BigDecimal safetyStockQty,
    String updatedBy,
    OffsetDateTime updatedAt
) {
}
