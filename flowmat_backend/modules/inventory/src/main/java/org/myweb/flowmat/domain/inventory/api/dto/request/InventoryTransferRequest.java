package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Moves stock from one record to another place (docs/domain/stock-transfer.md).
 *
 * @param toLocation where the stock goes; blank means "no location". Must differ from where it is.
 * @param requestId  client-generated idempotency key: a retry with the same key returns the first transfer.
 */
public record InventoryTransferRequest(
    @NotBlank String fromInventoryId,
    @Size(max = 100) String toLocation,
    @NotNull @Positive BigDecimal quantity,
    @NotBlank @Size(max = 100) String requestId,
    @Size(max = 500) String note
) {
}
