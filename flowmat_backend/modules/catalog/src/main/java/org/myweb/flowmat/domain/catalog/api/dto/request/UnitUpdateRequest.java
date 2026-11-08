package org.myweb.flowmat.domain.catalog.api.dto.request;

import java.math.BigDecimal;

/** Unit code and type are immutable once created because items and stock records refer to them. */
public record UnitUpdateRequest(
    String unitName,
    BigDecimal conversionRate,
    String activeYn
) {
}
