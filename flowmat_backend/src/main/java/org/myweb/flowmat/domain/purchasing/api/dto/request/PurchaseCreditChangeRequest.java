package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.math.BigDecimal;

/**
 * A change to a supplier credit kept for a return (docs/domain/purchasing.md PO52).
 *
 * @param amount     at least 0; empty when it was not credited after all, which withdraws it
 * @param creditNote the supplier's credit note number; optional, and dropped when withdrawing
 * @param reason     why it changes
 */
public record PurchaseCreditChangeRequest(BigDecimal amount, String creditNote, String reason) {
}
