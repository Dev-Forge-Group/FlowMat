package org.myweb.flowmat.domain.bom.api.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Material lines for a draft BOM from a spreadsheet (docs/domain/item-import.md "BOM 자재"). Values are text as they
 * were in the file, so a bad number is reported against its row.
 *
 * @param dryRun only check and say what would happen
 * @param replace remove the draft's current lines first; otherwise the file's lines are added to them
 * @param skipRows optional: rows to leave out, by their number among the data rows from 1. They are neither checked nor
 *                 added, and the other rows are checked as if these were not in the file (item-import.md B1). With
 *                 replace, a material of the draft that only a left-out row named is removed and not added back
 */
public record BomLineImportRequest(boolean dryRun, boolean replace,
                                   List<@NotNull(message = "rows must not contain null entries.") Row> rows,
                                   List<@NotNull(message = "skipRows must not contain null entries.") Integer> skipRows) {

    public BomLineImportRequest(boolean dryRun, boolean replace, List<Row> rows) {
        this(dryRun, replace, rows, null);
    }

    /**
     * @param itemCode the material's item code
     * @param unit a unit code such as "g"
     * @param lineType material (blank), by_product or waste (docs/domain/bom-by-products.md)
     * @param phantom Y (yes, true, 1) for a phantom sub-assembly; blank, N (no, false, 0) for none
     *     (docs/domain/multi-level-bom.md P1)
     * @param scrapPercent planned scrap as a percent of the quantity, as on screen: 5 or 5% is a rate of 0.05; blank for none
     *     (ADR-031)
     */
    public record Row(String itemCode, String quantity, String unit, String note, String lineType, String phantom,
                      String scrapPercent) {
        public Row(String itemCode, String quantity, String unit, String note, String lineType) {
            this(itemCode, quantity, unit, note, lineType, null, null);
        }

        public Row(String itemCode, String quantity, String unit, String note, String lineType, String phantom) {
            this(itemCode, quantity, unit, note, lineType, phantom, null);
        }
    }
}
