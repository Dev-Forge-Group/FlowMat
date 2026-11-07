package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.util.List;
import java.util.Optional;

/** Stock record facts for other contexts, such as an allocation's destination or a defect's scrap, without the repositories. */
public interface StockRecordQuery {
    record StockIdentity(String inventoryId, String itemId, String lotId, String location) {}
    Optional<StockIdentity> findProjectStock(String projectId, String inventoryId);
    /** The LOT's stock records that are not deleted. */
    List<String> findLotStockIds(String lotId);
}
