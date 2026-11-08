package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Descriptive fields of an item (docs/domain/item-details.md). Sent whole: every field replaces the stored one, so null or
 * blank clears it. Lengths are the column sizes.
 */
public record ItemDetails(
    @Size(max = 50, message = "itemGroup takes at most 50 characters.") String itemGroup,
    @Size(max = 200, message = "spec takes at most 200 characters.") String spec,
    /** Unique among the project's active items (409 otherwise). */
    @Size(max = 100, message = "barcode takes at most 100 characters.") String barcode,
    @Size(max = 100, message = "sku takes at most 100 characters.") String sku,
    @Size(max = 100, message = "storageCondition takes at most 100 characters.") String storageCondition,
    @Size(max = 2000, message = "description takes at most 2000 characters.") String description
) {
}
