package org.myweb.flowmat.domain.production.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a recorded run item is being cancelled; kept on the item and on the stock reversal.
 *
 * @param reallocate true to allocate what a cancelled input gives back to the run's work order again
 *                   (docs/domain/stock-allocation.md R4); otherwise it is free stock (R2)
 */
public record RunItemCancelRequest(@NotBlank(message = "reason is required.") @Size(max = 500) String reason, Boolean reallocate) {
}
