package org.myweb.flowmat.domain.catalog.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Items from a spreadsheet (docs/domain/item-import.md). Every value is text as it was in the file, so a bad number is
 * reported against its row instead of failing the whole request.
 *
 * @param dryRun only check and say what would happen
 */
public record ItemImportRequest(@NotBlank String projectId, boolean dryRun, List<@NotNull(message = "rows must not contain null entries.") Row> rows) {

    /**
     * One line of the file. A blank value leaves an existing item's field as it is; a new item gets the usual defaults.
     *
     * @param unitCode a unit's code such as "kg", not its id
     * @param lotTracked Y/N, yes/no, true/false or 1/0
     */
    public record Row(
        String itemCode,
        String itemName,
        String itemType,
        String resourceCategory,
        String unitCode,
        String itemStatus,
        String lotTracked,
        String safetyStockQty,
        String leadTimeDays,
        String unitCost,
        /** Details (docs/domain/item-details.md): a blank cell keeps the stored value, like every other column. */
        String itemGroup,
        String spec,
        /** Must not be another item's, nor appear twice in the file. */
        String barcode,
        String sku,
        String storageCondition,
        String description,
        /** What the item is bought in, e.g. "bag" (docs/domain/item-details.md "구매 단위"). */
        String purchaseUnit,
        /** Stock units in one purchase unit; needs a purchase unit, in the file or already set. */
        String purchaseUnitQty
    ) {
    }
}
