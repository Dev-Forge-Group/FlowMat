package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.util.Optional;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;

/**
 * Something outside the catalog that still relies on an item. Deleting the item is refused while any check says why
 * (docs/domain/inventory-bom-lot-contract.md "품목 삭제"). Other domains implement it, so the catalog does not have to
 * know about them. It is asked with the public view, so the implementations do not hold the catalog's entity
 * (docs/architecture/adr/ADR-018-entity-boundary.md R3).
 */
public interface ItemUsageCheck {

    /** Why the item cannot be deleted yet, said so the user knows what to do first; empty when nothing here uses it. */
    Optional<String> whyInUse(CatalogItemView item);
}
