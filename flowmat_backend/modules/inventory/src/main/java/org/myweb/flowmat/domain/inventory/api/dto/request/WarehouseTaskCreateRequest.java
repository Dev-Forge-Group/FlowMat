package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Plans moving part of one stock record (docs/domain/warehouse-task.md).
 *
 * @param taskType putaway (default) or pick
 * @param toLocation where the stock goes; a listed place when the project has a location list
 */
public record WarehouseTaskCreateRequest(
    @NotBlank String projectId,
    String taskType,
    @NotBlank String inventoryId,
    @NotNull @Positive BigDecimal quantity,
    @NotBlank @Size(max = 100) String toLocation,
    @Size(max = 500) String note
) {
}
