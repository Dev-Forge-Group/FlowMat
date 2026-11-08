package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * An approved BOM exploded through its materials' approved BOMs for a quantity of its item (docs/domain/multi-level-bom.md).
 * Quantities are gross (stock is not taken off) and in each item's own unit.
 *
 * @param quantity how much of the BOM's item, in that item's unit
 * @param levels the deepest level reached (1 when no material has a BOM of its own)
 * @param lines the tree, depth first: each material under the item it goes into
 * @param materials bought materials (no approved BOM) added up over the whole tree, by item code
 * @param materialCost the bought materials at their unit costs, 4 decimals
 * @param costComplete false when some bought material has no unit cost, so {@link #materialCost} leaves it out
 * @param problems sub-assemblies whose BOM could not be worked out; their materials are left out
 */
public record BomExplosionResponse(
    String bomId,
    Integer bomVersion,
    String targetItemId,
    String targetItemCode,
    BigDecimal quantity,
    int levels,
    List<Line> lines,
    List<Material> materials,
    BigDecimal materialCost,
    boolean costComplete,
    List<String> problems,
    /** The project calendar day whose revisions were used below this one (docs/domain/multi-level-bom.md M7). */
    java.time.LocalDate asOf
) {

    /** {@code bomId} and {@code bomVersion} are the material's own approved BOM when it is a sub-assembly. */
    public record Line(
        int level,
        String parentItemId,
        String itemId,
        String itemCode,
        String itemName,
        BigDecimal quantity,
        String unit,
        String bomId,
        Integer bomVersion
    ) {
    }

    public record Material(
        String itemId,
        String itemCode,
        String itemName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitCost,
        BigDecimal cost
    ) {
    }
}
