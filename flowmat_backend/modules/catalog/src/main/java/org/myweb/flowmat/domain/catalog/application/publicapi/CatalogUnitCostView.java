package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A project item's unit price at the requested instant. Null/zero prices remain unknown, not free. */
public record CatalogUnitCostView(String itemId, BigDecimal unitCost, OffsetDateTime costBasisAt, CostBasis costBasis) {
    public enum CostBasis { CURRENT, HISTORICAL, ESTIMATED }
}
