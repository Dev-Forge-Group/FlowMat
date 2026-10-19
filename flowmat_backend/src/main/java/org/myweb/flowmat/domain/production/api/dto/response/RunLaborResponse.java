package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Who worked on a run and for how long (docs/domain/worker-calendar.md LB4). There are no worker rates, so there is no cost.
 *
 * @param workedMinutes minutes of the records that count (not cancelled)
 * @param people        the work order's crew in its order, then anyone else with records in the order they were first recorded
 * @param lines         every record, cancelled ones included, oldest first
 * @param steps         the run's process steps in order, gateways left out, each with the minutes that count on it (LB15);
 *                      empty for a run without steps
 */
public record RunLaborResponse(String productionRunId, int workedMinutes, List<Person> people, List<Line> lines, List<Step> steps) {

    /**
     * @param sharePercent     their share in the work order's crew; null when they are not in it
     * @param workedMinutes    their minutes that count
     * @param suggestedMinutes for the crew, their share of their working time during the run (of the run's whole time when
     *                         they have no shifts) less what counts for them; null when nothing is left or not in the crew
     * @param suggestedFrom    {@code shifts} or {@code run}, as the suggestion is reckoned; null outside the crew
     */
    public record Person(String userId, Integer sharePercent, int workedMinutes, Integer suggestedMinutes, String suggestedFrom) {
    }

    public record Line(
        String runLaborId,
        String userId,
        int workedMinutes,
        String note,
        String recordedBy,
        OffsetDateTime recordedAt,
        boolean cancelled,
        String cancelledBy,
        OffsetDateTime cancelledAt,
        String cancelReason,
        /** The process step it was worked on and its name; null for the whole run (LB13). */
        String stepId,
        String stepName
    ) {
    }

    /** A process step of the run: when it started and ended, and the worked minutes that count on it. */
    public record Step(String stepId, String nodeName, Integer sequenceNo, String status, OffsetDateTime startedAt,
                       OffsetDateTime endedAt, int workedMinutes) {
    }
}
