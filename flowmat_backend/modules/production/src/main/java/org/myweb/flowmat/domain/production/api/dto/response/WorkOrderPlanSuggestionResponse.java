package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Suggested planned start and end of a work order on its equipment (docs/domain/equipment-schedule.md "계획 기간 제안"), or
 * without equipment in its crew's time by its step plan (docs/domain/worker-calendar.md LB25). Nothing is changed; a draft
 * takes the dates through the usual work order update.
 */
public record WorkOrderPlanSuggestionResponse(
    String workOrderId,
    /** Null when the order has no equipment and its crew's time decided (LB25). */
    String equipmentId,
    /** Where the search began: the requested time, else the planned start if still ahead, else now. */
    OffsetDateTime from,
    OffsetDateTime plannedStartAt,
    OffsetDateTime plannedEndAt,
    /** Null without a target quantity, which only an order without equipment can be planned without. */
    BigDecimal remainingQuantity,
    /** Null without equipment. */
    BigDecimal capacityPerHour,
    /** Remaining quantity at the capacity per hour, rounded up to 0.01 h; null without equipment. */
    BigDecimal productionHours,
    BigDecimal changeoverHours,
    /** The longer of production and labor, plus changeover: the available time between the start and the end. */
    BigDecimal neededHours,
    /** The order whose item this one changes over from; null without a changeover. */
    String changeoverFrom,
    /** Approved or running orders on the same equipment the suggestion was moved after, earliest first. */
    List<String> movedPast,
    /** The order's crew and each person's shift hours in the suggested window (docs/domain/worker-calendar.md WC14). */
    List<Crew> crew,
    /** Crew members with shifts who were not working when the equipment alone could have started (WC25). */
    List<String> waitedForCrew,
    /** Approved or running orders that already hold some of the crew, which the suggestion was moved after (WC25). */
    List<CrewBooking> crewMovedPast,
    /** The equipment's efficiency (docs/domain/equipment.md E8); null for 100. productionHours is at capacity times this. */
    BigDecimal efficiencyPercent,
    /** What is left of the step plan in worker minutes (docs/domain/worker-calendar.md LB25); null without a step plan. */
    Integer laborMinutes,
    /** How long that takes the crew with shifts, each doing their share while they work (LB27); to 0.01 h up, null when not known. */
    BigDecimal laborHours,
    /** Who works on which process when, for a step plan split among people (docs/domain/worker-calendar.md LB35); empty otherwise. */
    List<Turn> schedule
) {
    /**
     * One person's time on one process: from their first start on it to their last end.
     *
     * @param nodeName the process's name as planned
     */
    public record Turn(String nodeId, String nodeName, String userId, OffsetDateTime from, OffsetDateTime to) {
    }


    public record CrewBooking(String workOrderNumber, List<String> userIds) {
    }

    /** @param calendarSet false when the person has no shifts, so their hours are not known */
    public record Crew(String userId, boolean calendarSet, BigDecimal hours, int sharePercent) {
    }
}
