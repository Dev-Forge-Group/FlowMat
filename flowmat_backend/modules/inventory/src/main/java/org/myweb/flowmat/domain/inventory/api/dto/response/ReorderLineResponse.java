package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;

/**
 * An item whose usable stock across all its records is below its safety stock (docs/domain/stock-alert.md "재주문
 * 목록"). {@code unit} is the item's unit.
 */
public record ReorderLineResponse(
    String itemId,
    String itemCode,
    String itemName,
    String unit,
    BigDecimal safetyStockQty,
    BigDecimal availableQuantity,
    BigDecimal shortageQuantity,
    Integer leadTimeDays,
    /** The place the line is for (PS4), as the location list spells it; null on the item-level list. */
    String location
) {
}
