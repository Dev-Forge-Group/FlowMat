package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock received on a line going back to the supplier (docs/domain/purchasing.md PO26).
 *
 * @param inventoryId the stock record it leaves
 * @param quantity    in the item's stock unit
 * @param nonconformityId the open NCR that decided to send it back (PO42); optional
 * @param replacement true when the supplier replaces it on the same line (PO46); left out, it is not replaced there
 */
public record PurchaseReturnRequest(UUID requestId, String lineId, String inventoryId, BigDecimal quantity, String reason,
                                    String nonconformityId, Boolean replacement) {
}
