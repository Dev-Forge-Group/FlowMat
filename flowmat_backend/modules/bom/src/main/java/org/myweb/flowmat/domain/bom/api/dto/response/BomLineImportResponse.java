package org.myweb.flowmat.domain.bom.api.dto.response;

import java.util.List;

/**
 * What a BOM line import did or would do.
 *
 * @param applied true when the lines were saved: not a dry run and no row had an error
 * @param removed lines of the draft that are (or would be) removed because the import replaces them
 */
public record BomLineImportResponse(boolean dryRun, boolean applied, int added, int removed, int errors, List<RowResult> rows) {

    /**
     * @param row the data row's number, from 1
     * @param action add or error
     */
    public record RowResult(int row, String itemCode, String action, String message) {
    }
}
