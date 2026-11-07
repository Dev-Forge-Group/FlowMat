package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.util.List;

/**
 * What an item import did or would do (docs/domain/item-import.md).
 *
 * @param applied true when the changes were saved: not a dry run and no row had an error
 */
public record ItemImportResponse(
    boolean dryRun,
    boolean applied,
    int created,
    int updated,
    int unchanged,
    int errors,
    List<RowResult> rows
) {

    /**
     * @param row the row's number among the data rows, from 1
     * @param action create, update, unchanged or error
     * @param message what changes for an update, what is wrong for an error
     */
    public record RowResult(int row, String itemCode, String action, String message) {
    }
}
