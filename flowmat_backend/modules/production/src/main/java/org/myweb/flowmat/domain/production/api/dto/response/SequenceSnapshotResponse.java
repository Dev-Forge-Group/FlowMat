package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One equipment's work orders in one window, as the input for comparing orders of them
 * (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md decision 2), with what the plan and the current rule
 * give. Items are ids; nothing in it names a person or a price.
 *
 * @param itemBefore  the item the equipment runs before the first job; null when none
 * @param available   the equipment's available time from {@code from} to {@code horizonEnd}, earliest first
 * @param jobs        in planned order; {@code fixed} ones (running) stay first whatever the order
 * @param changeovers minutes from one item (outer key) to the next (inner key), every pair of {@code itemBefore} and
 *                    the jobs' items; 0 when no rule applies
 */
public record SequenceSnapshotResponse(int schemaVersion, String projectId, String equipmentId, OffsetDateTime from,
                                       OffsetDateTime to, OffsetDateTime horizonEnd, String itemBefore, List<Slot> available,
                                       List<Job> jobs, Map<String, Map<String, Integer>> changeovers, Weights weights,
                                       List<LeftOut> leftOut, Baseline plan, Baseline currentRule) {

    public static final int SCHEMA_VERSION = 1;

    public record Slot(OffsetDateTime start, OffsetDateTime end) {
    }

    /** {@code productionSeconds} is what is left at the equipment's real output, rounded up to the second. */
    public record Job(String workOrderId, String workOrderNumber, String item, String status, boolean fixed, BigDecimal remaining,
                      long productionSeconds, OffsetDateTime plannedStart, OffsetDateTime plannedEnd, String priority) {
    }

    /** What a changeover minute and a minute late of each priority cost. */
    public record Weights(BigDecimal changeoverMinute, Map<String, BigDecimal> lateMinute) {
    }

    public record LeftOut(String workOrderNumber, String reason) {
    }

    /**
     * An order of the jobs and what it costs. {@code objective} is null when some job does not fit before
     * {@code horizonEnd} ({@code unplaced} counts them).
     */
    public record Baseline(List<String> order, int changeoverMinutes, long lateMinutes, BigDecimal weightedLateMinutes,
                           BigDecimal objective, int unplaced, List<Placement> placements) {
    }

    /** {@code start} and {@code end} are null for a job that does not fit. */
    public record Placement(String workOrderNumber, OffsetDateTime start, OffsetDateTime end, int changeoverMinutes,
                            Long lateMinutes) {
    }
}
