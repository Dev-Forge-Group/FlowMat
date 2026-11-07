package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Stock to issue or reserve from an item's LOTs, first-expiring LOT first (docs/domain/lot-expiry.md "재고 출고 나눠 하기").
 *
 * @param unit optional; the quantity is converted to the item's unit like a run recording
 * @param action {@code issue} (the default) takes the stock away; {@code reserve} holds it on the records instead
 * @param requestId client-generated idempotency key: a retry with the same key returns the first result
 */
public record FefoIssueRequest(
    @NotBlank String projectId,
    @NotBlank String itemId,
    @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "quantity must be more than 0.") BigDecimal quantity,
    String unit,
    @Size(max = 500, message = "note takes at most 500 characters.") String note,
    @NotBlank @Size(max = 60, message = "requestId takes at most 60 characters.") String requestId,
    @Pattern(regexp = "(?i)issue|reserve", message = "action is issue or reserve.") String action
) {
}
