package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.Valid;
import java.math.BigDecimal;

public record ItemUpdateRequest(
    String itemName,
    String itemType,
    String resourceCategory,
    String resourceType,
    String unitId,
    String itemStatus,
    /** "Y": stock, receipts and production of this item must name a LOT. Defaults to "N". */
    String lotManageYn,
    /** Omitted: unchanged. 0 stops watching. */
    BigDecimal safetyStockQty,
    /** Omitted: unchanged. */
    Integer leadTimeDays,
    /** Omitted: unchanged. */
    BigDecimal unitCost,
    /** Omitted: unchanged. Must not be another active item's code in the project (409). */
    String itemCode,
    /** Omitted: every detail unchanged. Present: replaces them all, so a null field is cleared. */
    @Valid ItemDetails details,
    /** Omitted: unchanged. Blank: bought in the stock unit again (clears the quantity too). */
    String purchaseUnit,
    /** Omitted: unchanged. More than 0; needs a purchase unit, given here or already set. */
    BigDecimal purchaseUnitQty,
    /** Omitted: unchanged. Applies to LOTs registered from now on (docs/domain/lot-release.md). */
    String lotReleaseRequiredYn
) {

    public ItemUpdateRequest(String itemName, String itemType, String resourceCategory, String resourceType, String unitId,
                             String itemStatus, String lotManageYn, BigDecimal safetyStockQty, Integer leadTimeDays, BigDecimal unitCost,
                             String itemCode, ItemDetails details, String purchaseUnit, BigDecimal purchaseUnitQty) {
        this(itemName, itemType, resourceCategory, resourceType, unitId, itemStatus, lotManageYn, safetyStockQty, leadTimeDays, unitCost,
            itemCode, details, purchaseUnit, purchaseUnitQty, null);
    }
}
