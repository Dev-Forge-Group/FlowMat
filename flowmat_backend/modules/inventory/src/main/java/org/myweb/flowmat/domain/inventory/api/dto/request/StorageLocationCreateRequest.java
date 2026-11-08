package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param locationType site, warehouse, zone, location or bin
 * @param parentLocationId a place of an outer kind in the same project; empty for a top-level place
 * @param storageCondition how the place keeps stock, e.g. "Chilled 2-8 C"; empty for none (docs/domain/storage-location.md SL1)
 */
public record StorageLocationCreateRequest(
    @NotBlank String projectId,
    @NotBlank @Size(max = 100) String locationCode,
    @Size(max = 100) String locationName,
    @NotBlank String locationType,
    String parentLocationId,
    @Size(max = 500) String note,
    @Size(max = 100) String storageCondition
) {
}
