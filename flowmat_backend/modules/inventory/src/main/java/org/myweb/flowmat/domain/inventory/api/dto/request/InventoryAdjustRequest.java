package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record InventoryAdjustRequest(
    @NotBlank String projectId,
    @NotBlank String itemId,
    @NotNull BigDecimal quantity,
    BigDecimal reservedQuantity,
    BigDecimal availableQuantity,
    String location,
    String inventoryStatus,
    BigDecimal minThreshold,
    BigDecimal maxThreshold,
    /** Required for updates and must match the version read; omitted on create. */
    Long expectedVersion,
    /** Required on create for LOT-tracked items, forbidden otherwise; a stock record never changes LOT. */
    String lotId
) {
}
