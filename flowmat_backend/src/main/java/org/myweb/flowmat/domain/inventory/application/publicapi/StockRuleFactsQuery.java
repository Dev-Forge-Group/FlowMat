package org.myweb.flowmat.domain.inventory.application.publicapi;

import java.util.Map;
import java.util.Optional;

/**
 * A stock record as flow rules read it: a read-only map of every property inventory keeps for it, named as on the record
 * (e.g. location, availableQuantity), so a rule path such as inventory.location means what it meant when rules were given
 * the record itself (docs/architecture/adr/ADR-018-entity-boundary.md decision 5). The caller checks project access.
 */
public interface StockRuleFactsQuery {

    /** The record's rule facts if it exists and is not deleted, whatever its project. */
    Optional<Map<String, Object>> findActiveStock(String inventoryId);
}
