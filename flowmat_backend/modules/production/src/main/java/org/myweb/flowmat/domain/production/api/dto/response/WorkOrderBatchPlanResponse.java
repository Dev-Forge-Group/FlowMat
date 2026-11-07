package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Suggested dates for several work orders, in the order asked (docs/domain/equipment-schedule.md P8-P11). Nothing is
 * changed; drafts take the dates through the usual update.
 *
 * @param from  the requested start of every search; null when each order started from its own default
 * @param lines one per order: its suggestion, or why it has none
 */
public record WorkOrderBatchPlanResponse(OffsetDateTime from, List<Line> lines) {

    /**
     * @param suggestion as a single suggestion, placed after the orders before it in the request; null with a problem
     * @param problem    why the order could not be placed (no equipment, no capacity, no quantity, no time); null otherwise
     */
    public record Line(
        String workOrderId,
        String workOrderNumber,
        String workOrderStatus,
        WorkOrderPlanSuggestionResponse suggestion,
        String problem
    ) {
    }
}
