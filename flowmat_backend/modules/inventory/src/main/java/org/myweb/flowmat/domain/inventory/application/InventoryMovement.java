package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;

/**
 * One stock movement for {@link InventoryCommandService}. Deltas are signed; {@code actorUserId} is the authenticated
 * user, never a value from a request body.
 */
public record InventoryMovement(
    String inventoryId,
    InventoryTransactionType type,
    BigDecimal quantityDelta,
    BigDecimal reservedDelta,
    String referenceType,
    String referenceId,
    String note,
    String requestId,
    String actorUserId
) {
}
