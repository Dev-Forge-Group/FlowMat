package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Stock received for another context, such as a purchase order (docs/architecture/adr/ADR-027-purchasing.md decision 6). */
public interface StockReceiptCommand {

    /**
     * Receives stock with the same checks and saving as an imported stock row (docs/domain/stock-import.md), in the caller's
     * transaction: into the record of the same item, place and LOT, or a new one, registering a new LOT number first. The
     * ledger names the caller's reference. Returns the stock record; refuses with 400 and the row's problems. The caller has
     * checked access.
     */
    String receive(StockReceipt receipt);

    /**
     * @param location   a listed place when the project lists any; empty for none
     * @param lotNo      required for a LOT-tracked item, refused for others
     * @param expiryDate only for a LOT
     */
    record StockReceipt(String projectId, String itemId, BigDecimal quantity, String location, String lotNo, LocalDate expiryDate,
                        String referenceType, String referenceId, String note) {
    }
}
