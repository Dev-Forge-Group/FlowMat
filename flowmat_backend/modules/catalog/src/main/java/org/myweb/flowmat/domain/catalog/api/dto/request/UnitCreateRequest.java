package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** Leave baseUnitCode empty to create a base unit of its type; conversionRate is then forced to 1. */
public record UnitCreateRequest(
    @NotBlank String unitCode,
    @NotBlank String unitName,
    @NotBlank String unitType,
    String baseUnitCode,
    BigDecimal conversionRate
) {
}
