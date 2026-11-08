package org.myweb.flowmat.domain.purchasing.api.dto.request;

import java.math.BigDecimal;

/**
 * What the supplier credited for a return (docs/domain/purchasing.md PO47).
 *
 * @param amount     at least 0, in the money of the order's prices
 * @param creditNote the supplier's credit note number; optional
 */
public record PurchaseCreditRequest(BigDecimal amount, String creditNote) {
}
