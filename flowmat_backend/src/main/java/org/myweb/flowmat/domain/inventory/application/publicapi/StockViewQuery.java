package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stock records for other bounded contexts, which read them here instead of the inventory repositories and entities
 * (docs/architecture/adr/ADR-018-entity-boundary.md). The caller checks project access.
 */
public interface StockViewQuery {

    /** The record if it exists and is not deleted, whatever its project. */
    Optional<StockView> findActiveStock(String inventoryId);

    /** Records by id, deleted ones included; ids that do not exist are left out. */
    List<StockView> findStocks(Collection<String> inventoryIds);

    /** The project's records that are not deleted, oldest first. */
    List<StockView> projectStocks(String projectId);

    /**
     * The item's records in the project, row-locked inside the caller's transaction (which must exist) and read after the
     * locks, so a movement that would change them waits for the caller.
     */
    List<StockView> lockItemStock(String projectId, String itemId);
}
