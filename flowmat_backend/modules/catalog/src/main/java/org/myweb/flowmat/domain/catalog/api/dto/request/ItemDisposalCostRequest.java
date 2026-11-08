package org.myweb.flowmat.domain.catalog.api.dto.request;

import java.math.BigDecimal;

/** Explicit null clears the cost. expectedVersion is the version loaded by the editor (docs/domain/bom-by-products.md WD2). */
public record ItemDisposalCostRequest(BigDecimal disposalCost, long expectedVersion) {
}
