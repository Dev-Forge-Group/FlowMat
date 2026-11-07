package org.myweb.flowmat.domain.inventory.application.publicapi;

/** Stock places for other contexts, such as where a purchase order is to arrive (docs/domain/purchasing.md PO39). */
public interface StockPlaceQuery {

    /**
     * The place as new stock would take it (docs/domain/storage-location.md L5): blank is none; without a location list, as
     * typed; with one, the listed code as the list spells it, and a missing (400) or inactive (409) place is refused.
     */
    String placeFor(String projectId, String location);
}
