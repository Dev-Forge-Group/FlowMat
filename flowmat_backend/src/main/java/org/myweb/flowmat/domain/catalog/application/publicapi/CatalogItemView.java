package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;

/**
 * What another bounded context may read about an item. {@code unitCode} and {@code unitType} are the code and type (e.g.
 * count) of {@code unitId}, null when the item has no unit or the unit is gone; {@code unitCost} is the current unit cost as
 * stored, null when unset;
 * {@code leadTimeDays} is how long an order takes to arrive, null when unknown; {@code itemStatus} is active, inactive or
 * discontinued (null on items saved before statuses, which count as active; see ItemStatusRule); {@code lotManageYn} is "Y"
 * when stock is tracked per LOT; {@code purchaseUnit} is what the item is bought in and {@code conversionRate} how many
 * stock units one holds, the unit null when it is bought in its stock unit.
 */
public record CatalogItemView(
    String itemId,
    String projectId,
    String itemCode,
    String itemName,
    String unitId,
    String unitCode,
    String unitType,
    BigDecimal unitCost,
    Integer leadTimeDays,
    String itemStatus,
    String lotManageYn,
    String purchaseUnit,
    BigDecimal conversionRate
) {
}
