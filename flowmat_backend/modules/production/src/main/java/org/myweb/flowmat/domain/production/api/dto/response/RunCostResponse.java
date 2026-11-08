package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;

/** Input cost computed from recordings and unit-price history, never an authoritative stored amount. */
public record RunCostResponse(
    String productionRunId,
    BigDecimal materialCost,
    boolean costComplete,
    BigDecimal outputQuantity,
    BigDecimal costPerUnit,
    List<Line> lines,
    OffsetDateTime costBasisAt,
    CostBasis costBasis,
    boolean estimated
) {
    /** One input item in its item unit. Unknown prices are null, independently of historical certainty. */
    public record Line(String itemId, String itemCode, String itemName, BigDecimal quantity,
        String unit, BigDecimal unitCost, BigDecimal cost, CostBasis costBasis) {}
}
