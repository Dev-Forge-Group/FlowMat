package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.util.List;

/**
 * What a stock import did or would do (docs/domain/stock-import.md).
 *
 * @param applied true when the stock was received: not a dry run and no row had an error
 * @param created rows that make a new stock record
 * @param received rows that receive into a record already at that place
 * @param newLots LOTs the file registers
 */
public record StockImportResponse(
    boolean dryRun,
    boolean applied,
    int created,
    int received,
    int newLots,
    int errors,
    List<RowResult> rows
) {

    /**
     * @param row the data row's number, from 1
     * @param action create, receive or error
     */
    public record RowResult(int row, String itemCode, String action, String message) {
    }
}
