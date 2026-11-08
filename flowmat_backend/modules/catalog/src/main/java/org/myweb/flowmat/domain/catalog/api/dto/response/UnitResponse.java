package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;

public record UnitResponse(
    String unitId,
    String unitCode,
    String unitName,
    String unitType,
    String baseUnitCode,
    BigDecimal conversionRate,
    String activeYn
) {
}
