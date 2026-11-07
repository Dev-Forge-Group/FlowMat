package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record AllocatedStockTransferRequest(
    @NotBlank @Size(max=50) String projectId,
    @NotBlank @Size(max=50) String workOrderId,
    @NotBlank @Size(max=50) String allocationId,
    @NotBlank @Size(max=50) String fromInventoryId,
    @NotBlank @Size(max=100) String toLocation,
    @NotNull @Positive @Digits(integer=10, fraction=4) BigDecimal quantity,
    @NotNull UUID requestId
) {}
