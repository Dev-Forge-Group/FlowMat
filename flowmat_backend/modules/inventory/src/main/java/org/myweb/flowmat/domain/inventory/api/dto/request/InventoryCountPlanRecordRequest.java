package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record InventoryCountPlanRecordRequest(@NotNull @Min(0) Long expectedEntryVersion, @NotNull BigDecimal countedQuantity) {}
