package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A work order's crew and each person's time in its planned window (docs/domain/worker-calendar.md WC12). */
public record WorkOrderCrewResponse(
    String workOrderId,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    List<Member> members
) {

    /**
     * @param sharePercent  the part of their working time in the window the order needs (WC36)
     * @param calendarSet   whether the person has shifts; null without a planned window to check
     * @param hoursInWindow their shift hours in the planned window, less days off and holidays; null without a window
     */
    public record Member(String userId, int sharePercent, Boolean calendarSet, BigDecimal hoursInWindow) {
    }
}
