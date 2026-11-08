package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * How each item's stock moves (docs/domain/stock-analysis.md): what was consumed over the last {@code days}, how long
 * the usable stock lasts at that rate, and how long the item has sat idle. Nothing is stored.
 *
 * @param from start of the consumption window; it ends now
 * @param location the listed place the analysis is limited to, with the places inside it (A5); null for the whole project
 */
public record StockMovementAnalysisResponse(int days, OffsetDateTime from, String location, List<Line> lines) {

    /**
     * One item that has stock or was consumed in the window, quantities in the item's unit.
     *
     * @param usableQuantity available (on hand − reserved) outside quarantine and closed or expired LOTs
     * @param consumedQuantity issued and put into production in the window, less what was reversed
     * @param averageDailyConsumption consumedQuantity / days
     * @param daysOfCover usableQuantity / averageDailyConsumption; null without consumption
     * @param idleDays whole days since the last consumption, or since the first receipt when never consumed
     * @param coverBelowLeadTime true when the stock runs out before a new order could arrive
     * @param consumedValue consumedQuantity × unit cost; null without a unit cost
     * @param abcClass A, B or C by consumedValue: the items making up the first 80% of the value used are A, the next
     *                 15% B, the rest C (so is anything not used). Null without a unit cost.
     */
    public record Line(
        String itemId,
        String itemCode,
        String itemName,
        String unit,
        BigDecimal onHandQuantity,
        BigDecimal usableQuantity,
        BigDecimal stockValue,
        BigDecimal consumedQuantity,
        BigDecimal averageDailyConsumption,
        BigDecimal daysOfCover,
        Integer leadTimeDays,
        boolean coverBelowLeadTime,
        OffsetDateTime lastConsumedAt,
        OffsetDateTime lastReceivedAt,
        Long idleDays,
        BigDecimal consumedValue,
        String abcClass
    ) {
    }
}
