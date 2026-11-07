package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the project's open work orders still need, material by material, against the stock that can be used
 * (docs/domain/material-requirements.md). Nothing is reserved by asking.
 *
 * @param orders open work orders counted: approved or in progress, with a BOM and quantity still to make
 * @param lines materials, the biggest shortfall first
 * @param problems work orders whose BOM could not be worked out, with the reason; they are left out of the lines
 */
public record MaterialRequirementResponse(int orders, List<Line> lines, List<String> problems) {

    /**
     * Quantities are in the material's own unit.
     *
     * @param usable available (on hand − reserved) outside quarantine and closed or expired LOTs
     * @param shortage required − usable − plannedSupply − onOrder, never below zero
     * @param plannedSupply what open (approved or running) work orders making this item still have to make
     * @param madeHere the item has its own approved BOM (a sub-assembly, docs/domain/multi-level-bom.md); what stock and
     *     planned supply do not cover is exploded into its materials' needs
     * @param onOrder what is ordered from suppliers and not received yet (docs/domain/purchasing.md PO8)
     * @param supplies plannedSupply work order by work order, so its timing can be told (docs/domain/purchasing.md PO25)
     */
    public record Line(
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        BigDecimal required,
        BigDecimal usable,
        BigDecimal shortage,
        List<Need> orders,
        BigDecimal plannedSupply,
        boolean madeHere,
        BigDecimal onOrder,
        List<Supply> supplies
    ) {
    }

    /** What one open work order making the item still has to make. */
    public record Supply(String workOrderId, String workOrderTitle, BigDecimal quantity) {
    }

    /**
     * One work order's share of a material, or (with {@code viaItemId}) the share that comes from making a short
     * sub-assembly; then the work order fields are null.
     */
    public record Need(String workOrderId, String workOrderTitle, BigDecimal required, String viaItemId, String viaItemCode) {
    }
}
