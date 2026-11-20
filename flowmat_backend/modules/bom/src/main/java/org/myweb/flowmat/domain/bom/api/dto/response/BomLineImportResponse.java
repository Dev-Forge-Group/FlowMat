package org.myweb.flowmat.domain.bom.api.dto.response;

import java.util.List;

/**
 * What a BOM line import did or would do.
 *
 * @param applied true when the lines were saved: not a dry run and no row had an error
 * @param removed lines of the draft that are (or would be) removed because the import replaces them
 * @param skipped rows the request left out; their action is "skipped"
 * @param skippedCurrent of those, rows naming a material the draft has now, when the import replaces: that material is
 *                       removed and not added back
 */
public record BomLineImportResponse(boolean dryRun, boolean applied, int added, int removed, int errors, int skipped,
                                    int skippedCurrent, List<RowResult> rows) {

    /**
     * @param row the data row's number, from 1
     * @param action add, error or skipped
     */
    public record RowResult(int row, String itemCode, String action, String message) {
    }
}
