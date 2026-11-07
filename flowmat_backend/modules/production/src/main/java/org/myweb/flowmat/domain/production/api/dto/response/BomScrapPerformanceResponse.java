package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Planned scrap against what a BOM revision's finished runs actually lost, material by material
 * (docs/architecture/adr/ADR-034-bom-scrap-performance.md). Worked out on each read; nothing is stored.
 *
 * @param runs the finished runs counted
 */
public record BomScrapPerformanceResponse(String bomId, Integer bomVersion, int runs, List<Line> lines) {

    /**
     * One material in its item's unit, over the runs that planned it, each scaled to its actual output (4 decimals).
     *
     * @param plannedScrapRate the line's scrap rate, 0 to 1
     * @param net what the output takes without scrap: planned ÷ (1 + plannedScrapRate)
     * @param planned the plan with its scrap
     * @param actual what went in, a substitute as its main material
     * @param actualLossRate actual ÷ net − 1; null when net is zero
     * @param complete false when some input could not be converted to the item's unit and is left out
     */
    public record Line(
        String itemId,
        String itemCode,
        String unit,
        BigDecimal plannedScrapRate,
        int runs,
        BigDecimal net,
        BigDecimal planned,
        BigDecimal actual,
        BigDecimal actualLossRate,
        boolean complete
    ) {
    }
}
