package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A stock count (docs/domain/stock-count.md): what was counted on each record, applied together.
 *
 * @param requestId client-generated idempotency key: a retry with the same key returns the first result.
 */
public record InventoryCountRequest(
    @NotBlank String projectId,
    @NotBlank @Size(max = 60) String requestId,
    @Size(max = 500) String note,
    @NotEmpty @Size(max = 500) List<@NotNull(message = "lines must not contain null entries.") @Valid Line> lines
) {

    /**
     * @param expectedQuantity the quantity on hand the counter saw; when given and the stock has moved since, the whole
     *                         count is refused so nothing is overwritten with an out-of-date number.
     */
    public record Line(
        @NotBlank String inventoryId,
        @NotNull BigDecimal countedQuantity,
        BigDecimal expectedQuantity
    ) {
    }
}
