package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * An item's safety stock at a place (docs/domain/stock-alert.md PS2).
 *
 * @param safetyStockQty more than zero sets it; none or zero removes it
 */
public record PlaceSafetyStockRequest(
    @NotBlank String projectId,
    @NotBlank String itemId,
    @NotBlank String locationId,
    BigDecimal safetyStockQty
) {
}
