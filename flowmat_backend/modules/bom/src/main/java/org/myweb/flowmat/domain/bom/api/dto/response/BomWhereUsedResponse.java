package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;

/** One BOM revision that uses an item as a line (docs/domain/inventory-bom-lot-contract.md §5, where-used). */
public record BomWhereUsedResponse(
    String bomId,
    String bomName,
    Integer bomVersion,
    String bomStatus,
    String targetItemId,
    String targetItemCode,
    String targetItemName,
    BigDecimal baseQuantity,
    String baseUnit,
    String bomLineId,
    BigDecimal lineQuantity,
    String lineUnit,
    BigDecimal scrapRate,
    /** material, by_product or waste: whether the BOM uses the item or gives it off. */
    String lineType
) {
}
