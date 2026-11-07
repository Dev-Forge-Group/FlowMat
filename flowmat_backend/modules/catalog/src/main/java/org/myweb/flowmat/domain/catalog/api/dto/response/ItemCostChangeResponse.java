package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One change of an item's unit cost; null or 0 is an unknown cost (docs/domain/material-cost.md "단가 이력"). */
public record ItemCostChangeResponse(
    BigDecimal previousUnitCost,
    BigDecimal unitCost,
    String changedBy,
    OffsetDateTime changedAt
) {
}
