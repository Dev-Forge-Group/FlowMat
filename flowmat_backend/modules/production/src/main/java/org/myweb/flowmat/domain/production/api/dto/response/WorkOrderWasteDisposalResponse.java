package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * What disposing of the waste a work order still has to give off is expected to cost, at today's disposal costs
 * (docs/domain/bom-by-products.md WD8). An estimate only: shown apart, never stored, never added to material cost.
 *
 * @param quantity the target quantity still to make, in the product's unit; null without a BOM and quantity
 * @param problem why nothing could be estimated (no BOM and quantity, or the BOM could not be used); null otherwise
 */
public record WorkOrderWasteDisposalResponse(String workOrderId, String bomId, BigDecimal quantity, BigDecimal disposalCost,
    boolean costComplete, List<Line> lines, String problem) {
    /** One waste item in its item unit. An unknown disposal cost or an unconvertible quantity leaves the cost null. */
    public record Line(String itemId, String itemCode, String itemName, BigDecimal quantity, String unit,
        BigDecimal unitDisposalCost, BigDecimal cost) {}
}
