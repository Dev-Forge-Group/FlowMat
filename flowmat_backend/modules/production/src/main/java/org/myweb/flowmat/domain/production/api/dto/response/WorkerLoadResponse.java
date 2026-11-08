package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Each worker's shift hours in [from, to) against the work orders that name them (docs/domain/worker-calendar.md WC18-WC20).
 */
public record WorkerLoadResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    List<Row> workers
) {

    public record Row(
        String userId,
        boolean owner,
        boolean calendarSet,
        BigDecimal shiftHours,
        /** Their working time inside each approved or running order's window, added up per order. */
        BigDecimal plannedHours,
        /** The same for draft orders. */
        BigDecimal draftHours,
        /** Planned over shift hours, one decimal; null when they have no shift time in the window. */
        BigDecimal loadPercent,
        boolean overloaded,
        /** Open orders that name them without a planned start and end. */
        int unplannedOrders,
        List<Order> orders,
        /** Approved or running orders that name them at the same time. */
        List<Overlap> overlaps
    ) {
    }

    public record Order(
        String workOrderId,
        String workOrderNumber,
        String workOrderTitle,
        String workOrderStatus,
        /** Their share of the order (WC36); the hours in the window are this part of their working time there. */
        int sharePercent,
        OffsetDateTime plannedStartAt,
        OffsetDateTime plannedEndAt,
        BigDecimal hoursInWindow
    ) {
    }

    /** @param hours their working time in the overlap; 0 for a worker without shifts, whose time is not known */
    public record Overlap(
        String firstWorkOrderNumber,
        String secondWorkOrderNumber,
        OffsetDateTime from,
        OffsetDateTime to,
        BigDecimal hours
    ) {
    }
}
