package org.myweb.flowmat.domain.bom.application.publicapi;

import java.math.BigDecimal;
import org.myweb.flowmat.domain.bom.application.BomTree;

/** A line of a {@link BomView}. */
public record BomLineView(
    String bomLineId,
    String childItemId,
    BigDecimal quantity,
    String unit,
    BigDecimal scrapRate,
    String optionalYn,
    String substituteGroup,
    Integer sortOrder,
    String note,
    /** material, by_product or waste (docs/domain/bom-by-products.md). */
    String lineType,
    /** A phantom sub-assembly, used through its own BOM (docs/domain/multi-level-bom.md P1). */
    boolean phantom,
    /** Order of preference inside the substitute group, lowest first (ADR-032). */
    Integer priority,
    /** The main line this one stands in for; null for a main line and for a line outside a group (ADR-032). */
    String substituteFor
) {

    /** Whether the line is a material rather than a by-product or waste; a line without a type is a material. */
    public boolean material() {
        return BomTree.isMaterial(lineType);
    }
}
