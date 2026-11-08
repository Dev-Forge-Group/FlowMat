package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One past stock count (docs/domain/stock-count.md "실사 이력"): the records it changed. Records counted without a
 * difference were not changed and are not listed.
 *
 * @param increase what the count added, over all records
 * @param decrease what the count took away, as a positive number
 * @param valueChange the differences at today's unit costs, signed
 * @param valueComplete false when a changed record's item has no unit cost; the value leaves it out
 * @param valueAtCount the differences at the unit costs of the count's own time, from the price history (CH1)
 * @param valueAtCountComplete false when a changed record's item had no known unit cost then
 * @param valueAtCountEstimated true when a price in it was not covered by the history (before its first recorded change)
 */
public record InventoryCountHistoryResponse(
    String countId,
    OffsetDateTime countedAt,
    String countedBy,
    String note,
    int adjusted,
    BigDecimal increase,
    BigDecimal decrease,
    BigDecimal valueChange,
    boolean valueComplete,
    BigDecimal valueAtCount,
    boolean valueAtCountComplete,
    boolean valueAtCountEstimated,
    List<Line> lines
) {

    /** @param difference counted − on hand before the count */
    public record Line(
        String inventoryId,
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        String location,
        String lotNo,
        BigDecimal difference
    ) {
    }
}
