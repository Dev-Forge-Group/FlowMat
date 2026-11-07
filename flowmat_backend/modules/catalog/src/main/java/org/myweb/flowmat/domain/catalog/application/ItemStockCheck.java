package org.myweb.flowmat.domain.catalog.application;

/**
 * Whether stock records exist for an item, asked before a change that would leave existing records inconsistent, such
 * as switching LOT tracking. Inventory implements it, so the catalog does not depend on inventory: inventory already
 * depends on the catalog, and the reverse would close a cycle between the two (same pattern as {@link ItemUsageCheck}).
 */
public interface ItemStockCheck {

    /** Whether the item has a stock record that is not deleted, holding stock or not. */
    boolean hasStockRecords(String itemId);
}
