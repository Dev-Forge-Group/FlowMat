package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Material cost per unit of every item the project makes, rolled up through its approved BOMs (docs/domain/multi-level-bom.md
 * "반제품 원가 누적"). Read only; applying a figure as the item's unit cost is an ordinary item update.
 *
 * @param items made items, sub-assemblies (fewer levels) first
 */
public record BomCostRollupResponse(List<Line> items, java.time.LocalDate asOf) {

    /**
     * @param unit the item's unit, which both costs are per
     * @param levels levels of approved BOMs below the item: 1 when it is made from bought materials only
     * @param rolledUpCost bought materials' unit costs times what one unit takes through every level, 4 decimals; it leaves
     *     out what has no cost, so it is a lower bound unless {@code complete}
     * @param complete every bought material on the way has a unit cost and every quantity converts
     * @param missingCosts codes of bought materials with no unit cost
     * @param problems BOM lines whose quantity could not be converted
     * @param currentUnitCost the item's own unit cost now; null when it has none
     */
    public record Line(
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        String bomId,
        Integer bomVersion,
        int levels,
        BigDecimal rolledUpCost,
        boolean complete,
        List<String> missingCosts,
        List<String> problems,
        BigDecimal currentUnitCost
    ) {
    }
}
