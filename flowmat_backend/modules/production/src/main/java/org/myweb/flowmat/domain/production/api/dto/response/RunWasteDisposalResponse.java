package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogUnitCostView.CostBasis;

/**
 * What disposing of a run's recorded waste costs (docs/domain/bom-by-products.md WD5). Shown apart from material cost and
 * by-product value, never added to either, never stored as an authoritative amount.
 */
public record RunWasteDisposalResponse(String productionRunId, BigDecimal disposalCost, boolean costComplete,
    List<Line> lines, OffsetDateTime costBasisAt, CostBasis costBasis, boolean estimated) {
    /** One waste item in its item unit. An unknown disposal cost or an unconvertible quantity leaves the cost null. */
    public record Line(String itemId, String itemCode, String itemName, BigDecimal quantity, String unit,
        BigDecimal unitDisposalCost, BigDecimal cost, CostBasis costBasis) {}
}
