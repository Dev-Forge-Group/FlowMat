package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * How a run's material use compares with its BOM (docs/domain/material-cost.md "사용량 차이"). The BOM plan frozen when
 * the run started is scaled to the output: the actual output once the run has one, the planned output before that.
 * Nothing is stored.
 *
 * @param basisQuantity the output the standard is worked out for
 * @param basisIsActual true when that is the run's actual output
 * @param varianceCost the lines' variance costs added up; over zero means more was spent than the BOM allows
 * @param varianceCostComplete false when a line with a variance has no unit cost or unconvertible recordings
 */
public record RunMaterialUsageResponse(
    String productionRunId,
    String bomId,
    Integer bomVersion,
    BigDecimal plannedOutputQty,
    BigDecimal basisQuantity,
    boolean basisIsActual,
    BigDecimal varianceCost,
    boolean varianceCostComplete,
    List<Line> lines
) {

    /**
     * One input item in its own unit. An item used without being in the BOM has no plan, a standard of zero and no
     * percentage. A substitute the BOM approves is in the BOM: it takes a share of its main material's standard (ADR-032).
     *
     * @param actual null when a recording's unit cannot be converted to the item's unit
     * @param variancePercent variance / standard × 100; null when the standard is zero or the actual is unknown
     * @param substituteFor the main material this item stood in for; null for any other item
     * @param optional an optional material of the run's BOM that its work order did not pick, so not planned (ADR-033)
     */
    public record Line(
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        boolean inBom,
        BigDecimal planned,
        BigDecimal standard,
        BigDecimal actual,
        BigDecimal variance,
        BigDecimal variancePercent,
        BigDecimal unitCost,
        BigDecimal varianceCost,
        String substituteFor,
        boolean optional
    ) {
    }
}
