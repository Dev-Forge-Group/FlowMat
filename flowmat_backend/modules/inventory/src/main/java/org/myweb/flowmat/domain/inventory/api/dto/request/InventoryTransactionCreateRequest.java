package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One stock movement command (docs/domain/inventory-bom-lot-contract.md §2).
 *
 * @param quantity  positive amount; the transaction type decides the direction. Not used for quarantine/unquarantine.
 * @param direction {@code increase} or {@code decrease}; required for adjustment only.
 * @param requestId client-generated idempotency key: a retry with the same key returns the first result.
 */
public record InventoryTransactionCreateRequest(
    @NotBlank String inventoryId,
    @NotBlank String transactionType,
    BigDecimal quantity,
    String direction,
    @NotBlank @Size(max = 100) String requestId,
    String referenceType,
    String referenceId,
    String note
) {
}
