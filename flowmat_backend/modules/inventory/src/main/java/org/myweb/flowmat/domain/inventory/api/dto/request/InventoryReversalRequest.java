package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reverses one transaction by adding its opposite (docs/domain/inventory-bom-lot-contract.md §3). */
public record InventoryReversalRequest(
    @NotBlank @Size(max = 100) String requestId,
    @NotBlank @Size(max = 500) String reason
) {
}
