package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/** Disposal costs per item unit (docs/domain/bom-by-products.md WD3); callers enforce project access. */
public interface CatalogDisposalCostQuery {
    /**
     * Disposal costs of the project's items at the instant (null: now), deleted items still used by records included. Zero
     * is a known cost (free disposal); null is unknown. Before the first recorded change, or without history, a past instant
     * gets the cost as it was first known, marked ESTIMATED. Foreign or missing items are left out.
     */
    Map<String, CatalogUnitCostView> findDisposalCostsAt(String projectId, Collection<String> itemIds, OffsetDateTime basisAt);
}
