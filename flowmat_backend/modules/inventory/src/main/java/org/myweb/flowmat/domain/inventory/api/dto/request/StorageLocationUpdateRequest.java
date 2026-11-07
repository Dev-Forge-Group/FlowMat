package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Only the fields given change. An empty name or note clears it.
 *
 * @param parentLocationId move inside this place
 * @param clearParent true moves the place to the top level
 * @param active false stops new stock going there; allowed only once it holds no stock
 * @param storageCondition how the place keeps stock; an empty one clears it (SL1)
 */
public record StorageLocationUpdateRequest(
    @Size(max = 100) String locationCode,
    @Size(max = 100) String locationName,
    String locationType,
    String parentLocationId,
    Boolean clearParent,
    Boolean active,
    @Size(max = 500) String note,
    @Size(max = 100) String storageCondition
) {
}
