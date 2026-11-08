package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record InventoryTransactionResponse(
    String inventoryTransactionId,
    String inventoryId,
    String projectId,
    String itemId,
    String transactionType,
    BigDecimal quantityDelta,
    BigDecimal reservedDelta,
    BigDecimal availableDelta,
    BigDecimal quantityAfter,
    BigDecimal reservedAfter,
    BigDecimal availableAfter,
    String referenceType,
    String referenceId,
    String note,
    String createdBy,
    OffsetDateTime createdAt,
    String lotId,
    String requestId
) {
}
