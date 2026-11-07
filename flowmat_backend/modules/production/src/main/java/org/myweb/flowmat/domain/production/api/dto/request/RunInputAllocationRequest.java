package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * An input to take from the item's LOT stock, first-expiring LOT first (docs/domain/lot-expiry.md "여러 LOT에 나눠 투입").
 *
 * @param unit the unit the quantity is in; converted to the item's unit like any recording
 * @param processId optional, as on an ordinary recording
 */
public record RunInputAllocationRequest(
    @NotBlank String itemId,
    @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "quantity must be more than 0.") BigDecimal quantity,
    @NotBlank(message = "unit is required.") String unit,
    String processId
) {
}
