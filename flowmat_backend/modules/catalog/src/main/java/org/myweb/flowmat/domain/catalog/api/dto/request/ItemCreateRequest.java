package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record ItemCreateRequest(
    @NotBlank String projectId,
    @NotBlank String itemCode,
    @NotBlank String itemName,
    String itemType,
    String resourceCategory,
    String resourceType,
    String unitId,
    String itemStatus,
    /** "Y": stock, receipts and production of this item must name a LOT. Defaults to "N". */
    String lotManageYn,
    /** Stock to keep across all records; 0 or empty means not watched. */
    BigDecimal safetyStockQty,
    Integer leadTimeDays,
    /** Cost of one unit in the item's unit; omitted means not known. */
    BigDecimal unitCost,
    /** Optional; omitted leaves every detail empty. */
    @Valid ItemDetails details,
    /** What the item is bought in, e.g. "bag"; omitted: bought in its stock unit. */
    String purchaseUnit,
    /** Stock units in one purchase unit, more than 0; defaults to 1. */
    BigDecimal purchaseUnitQty,
    /** "Y": a new LOT waits for its receipt checks before its stock can be used (docs/domain/lot-release.md). Defaults to "N". */
    String lotReleaseRequiredYn
) {

    public ItemCreateRequest(String projectId, String itemCode, String itemName, String itemType, String resourceCategory,
                             String resourceType, String unitId, String itemStatus, String lotManageYn, BigDecimal safetyStockQty,
                             Integer leadTimeDays, BigDecimal unitCost, ItemDetails details, String purchaseUnit,
                             BigDecimal purchaseUnitQty) {
        this(projectId, itemCode, itemName, itemType, resourceCategory, resourceType, unitId, itemStatus, lotManageYn, safetyStockQty,
            leadTimeDays, unitCost, details, purchaseUnit, purchaseUnitQty, null);
    }
}
