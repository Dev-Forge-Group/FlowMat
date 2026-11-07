package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;

public record BomLineResponse(
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
}
