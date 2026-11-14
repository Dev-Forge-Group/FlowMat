package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Quantity to move, destination scan, and the task quantity observed before starting this completion (W6/W12/W16). */
public record WarehouseTaskCompleteRequest(
    BigDecimal quantity,
    @Size(max = 100) String expectedToLocation,
    @DecimalMin("0.0001") @DecimalMax("9999999999.9999") BigDecimal expectedQuantity
) {
}
