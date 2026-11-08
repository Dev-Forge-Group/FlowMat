package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Stock lost in a period, by why (docs/domain/stock-analysis.md "폐기·손실"): written off as expired, scrapped from a
 * defect, or found missing by a stock count. Reversed movements do not count. Values use today's unit costs.
 *
 * @param valueComplete false when a lost item has no unit cost; the values leave it out
 */
public record StockWasteResponse(
    int days,
    OffsetDateTime from,
    OffsetDateTime to,
    BigDecimal value,
    boolean valueComplete,
    BigDecimal expiredValue,
    BigDecimal defectValue,
    BigDecimal countLossValue,
    List<Line> lines
) {

    /** Quantities in the item's unit; value is null when the item has no unit cost. */
    public record Line(String itemId, String itemCode, String itemName, String unit, BigDecimal expired, BigDecimal defect,
                       BigDecimal countLoss, BigDecimal total, BigDecimal value) {
    }
}
