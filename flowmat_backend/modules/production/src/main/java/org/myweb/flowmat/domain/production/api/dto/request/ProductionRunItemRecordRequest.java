package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductionRunItemRecordRequest(
    String processId,
    String processIoId,
    String inventoryId,
    @NotBlank String itemId,
    @NotBlank String direction,
    @NotNull BigDecimal plannedQty,
    BigDecimal actualQty,
    @NotBlank(message = "unit is required.") String unit
) {
}
