package org.myweb.flowmat.domain.bom.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Where an item is used at every level (docs/domain/multi-level-bom.md "다단계 역전개"): the approved BOMs that use it,
 * the approved BOMs that use those products, and so on up to the products nothing else uses.
 *
 * @param unit the searched item's unit, which every {@code perProductUnit} and {@code perUnit} is in
 * @param uses one entry per route and level, depth first: the item's direct uses, each followed by the uses of that
 *     product
 * @param topProducts products at the top of a route, with how much of the item one unit needs over all its routes
 * @param problems BOM lines whose quantity could not be converted; their routes have no quantity
 */
public record BomWhereUsedTreeResponse(
    String itemId,
    String itemCode,
    String unit,
    List<Use> uses,
    List<TopProduct> topProducts,
    List<String> problems,
    /** The project calendar day whose revisions were used (docs/domain/multi-level-bom.md M7). */
    java.time.LocalDate asOf
) {

    /**
     * @param level 1 when the searched item is a material of this BOM, 2 when a product made from it is, …
     * @param materialItemId the material of this BOM on the route: the searched item at level 1, else the product below
     * @param perProductUnit how much of the searched item one unit of the product needs along this route, in
     *     {@code unit}; null when a quantity on the route could not be converted
     * @param topLevel no approved BOM uses the product
     * @param path item codes from the searched item up to the product
     */
    public record Use(
        int level,
        String materialItemId,
        String materialItemCode,
        String productItemId,
        String productItemCode,
        String productItemName,
        String productUnit,
        String bomId,
        Integer bomVersion,
        BigDecimal lineQuantity,
        String lineUnit,
        BigDecimal baseQuantity,
        String baseUnit,
        BigDecimal perProductUnit,
        boolean topLevel,
        List<String> path
    ) {
    }

    /**
     * @param perUnit how much of the searched item one unit of the product needs, all routes added up; null when a route
     *     has no quantity
     * @param levels the longest route, in BOM levels
     * @param routes how many routes reach the product
     */
    public record TopProduct(String itemId, String itemCode, String itemName, String unit, BigDecimal perUnit, int levels, int routes) {
    }
}
