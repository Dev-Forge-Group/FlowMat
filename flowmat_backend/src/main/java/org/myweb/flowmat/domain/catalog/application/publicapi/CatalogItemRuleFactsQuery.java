package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.util.Map;
import java.util.Optional;

/**
 * An item as flow rules read it: a read-only map of every property the catalog keeps for it, named as on the item
 * (e.g. itemType, storageCondition), so a rule path such as item.storageCondition means what it meant when rules were
 * given the item itself (docs/architecture/adr/ADR-018-entity-boundary.md decision 5). The caller checks project access.
 */
public interface CatalogItemRuleFactsQuery {

    /** The item's rule facts if it exists and is not deleted, whatever its project. */
    Optional<Map<String, Object>> findActiveItem(String itemId);
}
