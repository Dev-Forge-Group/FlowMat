package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Stock moved between places in a period (docs/domain/stock-analysis.md "위치 간 이동"), per route, most moves first.
 * A null place is stock kept without one.
 */
public record StockTransferAnalysisResponse(
    int days,
    OffsetDateTime from,
    OffsetDateTime to,
    List<Route> routes
) {

    /** One way stock went, with every item moved along it; {@code moves} counts transfers. */
    public record Route(String fromLocation, String toLocation, long moves, List<Line> items) {
    }

    /** Quantity in the item's unit. */
    public record Line(String itemId, String itemCode, String itemName, String unit, BigDecimal quantity, long moves) {
    }
}
