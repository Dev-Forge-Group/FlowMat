package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Stock record reads for other bounded contexts, which use this instead of the inventory repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface StockQuery {

    /** The net stock change the ledger recorded for one source, such as a production run item; 0 without any. */
    BigDecimal quantityMovedBy(String referenceType, String referenceId);

    /** What a stock record that is not deleted holds less what is reserved; empty when there is no such record. */
    Optional<BigDecimal> availableQuantity(String inventoryId);
}
