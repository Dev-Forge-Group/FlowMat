package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;

/**
 * Stock movements other bounded contexts make, through the one place stock quantities change
 * (docs/domain/inventory-bom-lot-contract.md §1–3, docs/architecture/adr/ADR-036-cross-context-contract-types.md). Each
 * call runs in the caller's transaction and keeps every stock invariant, or refuses; access checks are the caller's job.
 */
public interface StockMovementCommand {

    /** Applies the movement if it keeps every stock invariant, and records it. */
    void apply(StockMovement movement);

    /**
     * Reverses every not-yet-reversed movement recorded for a source record (e.g. one production run item), checking
     * today's stock like any other movement. Returns how many were reversed.
     */
    int reverseMovementsOf(String referenceType, String referenceId, String reason, String actorUserId);

    /** The kinds of movement other contexts make; each is the inventory transaction type of the same name. */
    enum Kind {
        RESERVE,
        RELEASE,
        PRODUCTION_INPUT,
        PRODUCTION_OUTPUT,
        ADJUSTMENT,
        QUARANTINE
    }

    /**
     * One movement of a stock record: signed changes of its quantity and its reserved quantity, and the record that
     * caused it. A {@code requestId} already recorded is refused; null when the caller has none. {@code actorUserId} is
     * the authenticated user, never a value from a request body.
     */
    record StockMovement(
        String inventoryId,
        Kind kind,
        BigDecimal quantityDelta,
        BigDecimal reservedDelta,
        String referenceType,
        String referenceId,
        String note,
        String requestId,
        String actorUserId
    ) {
    }
}
