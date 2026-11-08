package org.myweb.flowmat.domain.bom.application.publicapi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Material needed for a production quantity: productionQuantity / baseQuantity × line quantity, then converted to
 * each material item's unit (docs/domain/inventory-bom-lot-contract.md §5).
 */
public record BomRequirement(
    String bomId,
    Integer bomVersion,
    String targetItemId,
    BigDecimal productionQuantity,
    /** Base quantity expressed in the target item's unit. */
    BigDecimal baseQuantity,
    List<Line> lines,
    /** Sum of the lines' costs, 4 decimals (docs/domain/material-cost.md). */
    BigDecimal materialCost,
    /** False when some material has no unit cost, so {@link #materialCost} leaves it out. */
    boolean costComplete,
    /** By-products and waste the batch gives off (docs/domain/bom-by-products.md); not in {@link #lines}. */
    List<Output> outputs,
    /**
     * Optional materials left out because no work order picked them, worked out like {@link #lines} but neither in them
     * nor in {@link #materialCost} (ADR-033).
     */
    List<Line> optionals
) {

    public BomRequirement {
        optionals = optionals == null ? List.of() : List.copyOf(optionals);
    }

    public BomRequirement(String bomId, Integer bomVersion, String targetItemId, BigDecimal productionQuantity,
                                  BigDecimal baseQuantity, List<Line> lines, BigDecimal materialCost, boolean costComplete,
                                  List<Output> outputs) {
        this(bomId, bomVersion, targetItemId, productionQuantity, baseQuantity, lines, materialCost, costComplete, outputs, List.of());
    }

    /** A by-product or waste line scaled like a material: in the line's unit and in the item's own unit (4 decimals). */
    public record Output(
        String bomLineId,
        String itemId,
        String lineType,
        BigDecimal lineQuantity,
        String lineUnit,
        BigDecimal quantity,
        String itemUnit,
        BigDecimal itemQuantity
    ) {
    }

    public record Line(
        String bomLineId,
        String childItemId,
        BigDecimal lineQuantity,
        String lineUnit,
        /** In the line's unit. */
        BigDecimal requiredQuantity,
        /** The material item's stock unit (the line unit when the item has none). */
        String itemUnit,
        /** In {@link #itemUnit}, 4 decimals. */
        BigDecimal requiredItemQuantity,
        /** Factor from lineUnit to itemUnit. */
        BigDecimal conversionRate,
        /** The material's cost per {@link #itemUnit}; null when not known. */
        BigDecimal unitCost,
        /** requiredItemQuantity × unitCost, 4 decimals; null when the unit cost is not known. */
        BigDecimal lineCost,
        /** The phantom sub-assembly this material comes through; null for the BOM's own lines (multi-level-bom.md P2). */
        String viaItemId,
        /** The line's planned scrap, 0 to 1; the required quantities already include it (ADR-031). */
        BigDecimal scrapRate,
        /** Materials the BOM approves in place of this one, in order of preference; not in the requirement (ADR-032). */
        List<Substitute> substitutes
    ) {

        public Line {
            substitutes = substitutes == null ? List.of() : List.copyOf(substitutes);
        }

        public Line(String bomLineId, String childItemId, BigDecimal lineQuantity, String lineUnit, BigDecimal requiredQuantity,
                    String itemUnit, BigDecimal requiredItemQuantity, BigDecimal conversionRate, BigDecimal unitCost,
                    BigDecimal lineCost, String viaItemId, BigDecimal scrapRate) {
            this(bomLineId, childItemId, lineQuantity, lineUnit, requiredQuantity, itemUnit, requiredItemQuantity, conversionRate,
                unitCost, lineCost, viaItemId, scrapRate, List.of());
        }

        public Line(String bomLineId, String childItemId, BigDecimal lineQuantity, String lineUnit, BigDecimal requiredQuantity,
                    String itemUnit, BigDecimal requiredItemQuantity, BigDecimal conversionRate, BigDecimal unitCost,
                    BigDecimal lineCost) {
            this(bomLineId, childItemId, lineQuantity, lineUnit, requiredQuantity, itemUnit, requiredItemQuantity, conversionRate,
                unitCost, lineCost, null);
        }

        public Line(String bomLineId, String childItemId, BigDecimal lineQuantity, String lineUnit, BigDecimal requiredQuantity,
                    String itemUnit, BigDecimal requiredItemQuantity, BigDecimal conversionRate, BigDecimal unitCost,
                    BigDecimal lineCost, String viaItemId) {
            this(bomLineId, childItemId, lineQuantity, lineUnit, requiredQuantity, itemUnit, requiredItemQuantity, conversionRate,
                unitCost, lineCost, viaItemId, BigDecimal.ZERO);
        }

        /**
         * How much of {@link #requiredItemQuantity} the given inputs already cover, 4 decimals: this material's own, plus
         * each substitute's as this material (ADR-032 decision 2). {@code used}: item id → quantity in that item's unit.
         */
        public BigDecimal covered(Map<String, BigDecimal> used) {
            BigDecimal covered = used.getOrDefault(childItemId, BigDecimal.ZERO);
            for (Substitute substitute : substitutes) {
                BigDecimal quantity = used.get(substitute.childItemId());
                if (quantity != null && substitute.mainPerUnit() != null) {
                    covered = covered.add(quantity.multiply(substitute.mainPerUnit()));
                }
            }
            return covered.setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * A material that may stand in for a line's whole requirement (ADR-032): what the same production quantity takes of it
     * instead, with its own scrap, in its line's unit and in its item's unit (4 decimals), and what that costs.
     */
    public record Substitute(
        String bomLineId,
        String childItemId,
        BigDecimal lineQuantity,
        String lineUnit,
        BigDecimal requiredQuantity,
        String itemUnit,
        BigDecimal requiredItemQuantity,
        /** The substitute's cost per {@link #itemUnit}; null when not known. */
        BigDecimal unitCost,
        /** requiredItemQuantity × unitCost, 4 decimals; null when the unit cost is not known. */
        BigDecimal lineCost,
        BigDecimal scrapRate,
        /** Order of preference in the group, lowest first; the main line has the lowest. */
        Integer priority,
        /** How much of the main material, in its item's unit, one item unit of this substitute stands for (12 decimals). */
        BigDecimal mainPerUnit
    ) {
    }
}
