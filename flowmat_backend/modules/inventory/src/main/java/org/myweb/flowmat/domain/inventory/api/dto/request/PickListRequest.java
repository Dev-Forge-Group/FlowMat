package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Plans picks to a staging place (docs/domain/warehouse-task.md): for a work order's BOM, or for given lines.
 *
 * @param workOrderId an approved or started order with a BOM; its materials are what to pick
 * @param quantity how much of the order to pick for; the order's target when empty
 * @param lines what to pick when there is no work order, in each item's stock unit
 */
public record PickListRequest(
    @NotBlank String projectId,
    @NotBlank @Size(max = 100) String stagingLocation,
    String workOrderId,
    @Positive BigDecimal quantity,
    @Valid List<@NotNull(message = "lines must not contain null entries.") Line> lines,
    @Size(max = 500) String note
) {

    public record Line(@NotBlank String itemId, @NotNull @Positive BigDecimal quantity) {
    }
}
