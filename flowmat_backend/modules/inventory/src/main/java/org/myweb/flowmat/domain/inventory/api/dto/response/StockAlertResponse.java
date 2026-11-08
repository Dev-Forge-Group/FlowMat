package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A stock alert with the names needed to show it; {@code unit} is the item's unit. */
public record StockAlertResponse(
    String stockAlertId,
    String projectId,
    String inventoryId,
    String itemId,
    String itemCode,
    String itemName,
    String location,
    String lotNo,
    String alertType,
    String severity,
    BigDecimal thresholdValue,
    BigDecimal actualValue,
    String unit,
    String message,
    boolean resolved,
    OffsetDateTime triggeredAt,
    OffsetDateTime resolvedAt
) {
}
