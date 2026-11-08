package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Writing off the stock of expired LOTs (docs/domain/lot-expiry.md "만료 재고 폐기").
 *
 * @param lotIds the LOTs to write off; empty or omitted means every expired LOT of the project that still holds stock
 * @param closeLots close each LOT whose stock is all gone afterwards; needs owner access, like closing a LOT
 * @param requestId client-generated idempotency key: a retry with the same key returns the first result
 */
public record ExpiredWriteOffRequest(
    @NotBlank String projectId,
    List<String> lotIds,
    @Size(max = 500, message = "note takes at most 500 characters.") String note,
    boolean closeLots,
    @NotBlank @Size(max = 60, message = "requestId takes at most 60 characters.") String requestId
) {
}
