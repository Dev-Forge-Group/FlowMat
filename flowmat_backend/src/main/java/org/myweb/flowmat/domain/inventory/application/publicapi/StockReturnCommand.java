package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.math.BigDecimal;

/** Stock sent back to a supplier for another context, such as a purchase order (docs/architecture/adr/ADR-027-purchasing.md decision 12). */
public interface StockReturnCommand {

    /**
     * Takes the quantity out of a stock record of the project as a decreasing adjustment that names the caller's reference, in
     * the caller's transaction. An adjustment, as a defect's scrap is, so quarantined stock (where goods to send back usually
     * sit) can go; reserved stock cannot (409), and a closed LOT's cannot. The caller has checked access, the item and the LOT.
     */
    void giveBack(StockReturn stockReturn);

    record StockReturn(String projectId, String inventoryId, BigDecimal quantity, String referenceType, String referenceId, String note) {
    }
}
