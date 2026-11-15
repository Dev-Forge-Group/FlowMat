package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Stock received from a spreadsheet, such as opening balances (docs/domain/stock-import.md). Values are text as they
 * were in the file, so a bad number or date is reported against its row.
 *
 * @param dryRun only check and say what would happen
 * @param note written on every receipt into an existing record
 * @param skipRows optional: rows to leave out, by their number among the data rows from 1. They are neither checked nor
 *                 received, and the other rows are checked as if these were not in the file (stock-import.md "줄을 골라 적용")
 */
public record StockImportRequest(@NotBlank String projectId, boolean dryRun, String note,
                                 List<@NotNull(message = "rows must not contain null entries.") Row> rows,
                                 List<@NotNull(message = "skipRows must not contain null entries.") Integer> skipRows) {

    /**
     * @param location blank means a record without a location; matched exactly, as stock records are
     * @param lotNo required for LOT-tracked items, not allowed for others; a new number registers the LOT
     * @param expiryDate yyyy-MM-dd, for a LOT the file registers
     * @param packs instead of a quantity: how many of the item's purchase units came in, e.g. 2 bags of 25 kg are 50 kg
     *              (docs/domain/item-details.md "구매 단위")
     */
    public record Row(String itemCode, String location, String lotNo, String quantity, String expiryDate, String packs) {
    }
}
