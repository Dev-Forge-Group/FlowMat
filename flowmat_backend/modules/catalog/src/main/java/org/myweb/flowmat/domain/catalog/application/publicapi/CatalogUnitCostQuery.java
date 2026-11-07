package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/** Internal catalog reads; callers enforce project access before requesting a price. */
public interface CatalogUnitCostQuery {
    /**
     * Prices of the requested project's items, including deleted items still used by historical records.
     * Null basisAt means current prices. A historical price before the first recorded change or without history
     * is ESTIMATED. A recorded unknown price stays unknown. Foreign/missing items are excluded.
     */
    Map<String, CatalogUnitCostView> findUnitCostsAt(String projectId, Collection<String> itemIds, OffsetDateTime basisAt);
}
