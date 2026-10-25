package org.myweb.flowmat.domain.production.api.dto.response;

import java.util.List;

/**
 * A work order's planned crew time beside the time its runs recorded for each person (docs/domain/worker-calendar.md LB6).
 *
 * @param plannedMinutes  the crew's known planned minutes; null when no one's is known
 * @param plannedComplete false when someone in the crew has no known planned time (no shifts, or no planned window)
 * @param runs            the order's physical runs
 * @param actualMinutes   what those runs recorded, cancelled records left out
 * @param people          the crew in its order, then anyone else with records
 * @param steps           the actual minutes on each process across those runs, in process order, then the processes planned
 *                        but not yet run in the plan's order (LB18, LB23)
 * @param wholeRunMinutes the actual minutes that name no step
 * @param stepPlannedMinutes the step plan added up (LB23); null without one
 */
public record WorkOrderLaborComparisonResponse(
    String workOrderId,
    Integer plannedMinutes,
    boolean plannedComplete,
    int runs,
    int actualMinutes,
    List<Person> people,
    List<Step> steps,
    int wholeRunMinutes,
    Integer stepPlannedMinutes
) {
    /**
     * @param sharePercent   null when they are not in the crew
     * @param plannedMinutes their share of their working time in the order's planned window (the hours the crew picker shows,
     *                       WC36); null when not known or not in the crew
     */
    public record Person(String userId, Integer sharePercent, Integer plannedMinutes, int actualMinutes) {
    }

    /**
     * A process of the workflow: the steps of the order's runs on it add up, whatever revision they ran.
     *
     * @param sequenceNo     null for a process that is planned but has not run yet
     * @param plannedMinutes the worker minutes planned for it (LB23); null when it is not in the step plan
     */
    public record Step(String nodeId, String nodeName, Integer sequenceNo, int actualMinutes, Integer plannedMinutes) {
    }
}
