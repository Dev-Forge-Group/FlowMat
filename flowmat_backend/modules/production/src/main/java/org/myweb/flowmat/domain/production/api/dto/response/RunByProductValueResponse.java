package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;

/** Separate recorded by-product value; never subtracted from material cost or stored as authoritative money. */
public record RunByProductValueResponse(String productionRunId, BigDecimal byProductValue, boolean valueComplete,
    List<Line> lines, OffsetDateTime costBasisAt, CostBasis costBasis, boolean estimated) {
    public record Line(String itemId, String itemCode, String itemName, BigDecimal quantity, String unit,
        BigDecimal unitCost, BigDecimal value, CostBasis costBasis) {}
}
