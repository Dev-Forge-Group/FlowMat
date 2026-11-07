package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.util.Collection;
import java.util.Map;

/** Items' storage conditions for other contexts, such as a place that refuses other conditions (docs/domain/storage-location.md SL5). */
public interface CatalogStorageQuery {

    /** By item id; items that do not exist are left out. */
    Map<String, ItemStorage> findStorage(Collection<String> itemIds);

    /** @param storageCondition as stored on the item; null when it has none */
    record ItemStorage(String itemCode, String storageCondition) {
    }
}
