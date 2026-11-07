package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Each item's stock over a period (docs/domain/stock-ledger.md "기간 수불"): what it held at the start, what came in and
 * went out by kind, and what it held at the end. Quantities are in the item's unit.
 *
 * @param from start of the period (not included); the opening balance is the stock at this moment
 * @param to end of the period (included); the closing balance is the stock at this moment
 */
public record StockMovementSummaryResponse(OffsetDateTime from, OffsetDateTime to, List<Line> lines) {

    /**
     * @param received receipts
     * @param produced production output
     * @param issued issues, as a positive number
     * @param consumed production input, as a positive number
     * @param transferred transfers in less transfers out; zero, as a transfer stays with the item
     * @param corrected adjustments and reversals, signed
     * @param unexplained closing − (opening + in − out + transferred + corrected); not zero only for records that
     *                    changed outside the ledger, such as rows from before it
     */
    public record Line(
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        BigDecimal opening,
        BigDecimal received,
        BigDecimal produced,
        BigDecimal issued,
        BigDecimal consumed,
        BigDecimal transferred,
        BigDecimal corrected,
        BigDecimal closing,
        BigDecimal unexplained
    ) {
    }
}
