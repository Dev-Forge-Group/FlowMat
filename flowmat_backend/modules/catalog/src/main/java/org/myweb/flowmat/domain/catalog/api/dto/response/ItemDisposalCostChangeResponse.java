package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One change of an item's disposal cost (docs/domain/bom-by-products.md WD7); null is unknown, zero is free. */
public record ItemDisposalCostChangeResponse(BigDecimal previousDisposalCost, BigDecimal disposalCost, String changedBy,
    OffsetDateTime changedAt) {
}
