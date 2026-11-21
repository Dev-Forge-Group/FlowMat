package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Baseline;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Job;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Placement;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Slot;

/**
 * What an order of one equipment's jobs costs (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md decision 1):
 * changeover minutes plus minutes late, each priority's minutes weighted. Jobs run one after another from the window's
 * start, each its changeover from the item before and then its production, filling the equipment's available time; a job
 * is late by how far it ends after its planned end, rounded up to the minute. A pure calculation over a snapshot, so the
 * same numbers come from the snapshot API, the fixture tests and any solver that mirrors it.
 */
public final class SequenceCost {

    /** Weights when the snapshot is made: a changeover minute is 1, a minute late is 4, 2, 1 or 0.5 by priority. */
    public static final SequenceSnapshotResponse.Weights WEIGHTS = new SequenceSnapshotResponse.Weights(BigDecimal.ONE,
        Map.of("urgent", BigDecimal.valueOf(4), "high", BigDecimal.valueOf(2), "normal", BigDecimal.ONE, "low", new BigDecimal("0.5")));

    private SequenceCost() {
    }

    /**
     * The cost of running the jobs in {@code order} (work order numbers, every job once). The fixed jobs must come first.
     *
     * @throws IllegalArgumentException when the order is not the snapshot's jobs once each, fixed ones first
     */
    public static Baseline of(SequenceSnapshotResponse snapshot, List<String> order) {
        Map<String, Job> byNumber = new HashMap<>();
        snapshot.jobs().forEach(job -> byNumber.put(job.workOrderNumber(), job));
        if (order.size() != byNumber.size() || !byNumber.keySet().containsAll(order) || order.stream().distinct().count() != order.size()) {
            throw new IllegalArgumentException("An order names each of the snapshot's jobs once");
        }
        long fixed = snapshot.jobs().stream().filter(Job::fixed).count();
        for (int index = 0; index < fixed; index++) {
            if (!byNumber.get(order.get(index)).fixed()) throw new IllegalArgumentException("Running jobs stay first");
        }
        List<Slot> slots = snapshot.available();
        OffsetDateTime cursor = snapshot.from();
        String previous = snapshot.itemBefore();
        int changeoverMinutes = 0;
        long lateMinutes = 0;
        BigDecimal weightedLate = BigDecimal.ZERO;
        int unplaced = 0;
        List<Placement> placements = new ArrayList<>();
        for (String number : order) {
            Job job = byNumber.get(number);
            int changeover = previous == null ? 0 : minutes(snapshot, previous, job.item());
            previous = job.item();
            changeoverMinutes += changeover;
            if (cursor == null) {
                unplaced++;
                placements.add(new Placement(number, null, null, changeover, null));
                continue;
            }
            OffsetDateTime[] span = fill(slots, cursor, changeover * 60L + job.productionSeconds());
            if (span == null) {
                unplaced++;
                cursor = null;
                placements.add(new Placement(number, null, null, changeover, null));
                continue;
            }
            cursor = span[1];
            long late = Math.max(0, ceilMinutes(Duration.between(job.plannedEnd(), span[1]).getSeconds()));
            lateMinutes += late;
            weightedLate = weightedLate.add(weight(snapshot, job.priority()).multiply(BigDecimal.valueOf(late)));
            placements.add(new Placement(number, span[0], span[1], changeover, late));
        }
        BigDecimal objective = unplaced > 0 ? null
            : snapshot.weights().changeoverMinute().multiply(BigDecimal.valueOf(changeoverMinutes)).add(weightedLate);
        return new Baseline(List.copyOf(order), changeoverMinutes, lateMinutes, weightedLate, objective, unplaced, placements);
    }

    /**
     * Where {@code seconds} of available time from {@code from} begin and end; null when the slots do not hold them. A job
     * of no time begins and ends at the first available moment.
     */
    static OffsetDateTime[] fill(List<Slot> slots, OffsetDateTime from, long seconds) {
        OffsetDateTime start = null;
        long left = seconds;
        for (Slot slot : slots) {
            if (!slot.end().isAfter(from)) continue;
            OffsetDateTime begin = slot.start().isAfter(from) ? slot.start() : from;
            if (start == null) start = begin;
            long room = Duration.between(begin, slot.end()).getSeconds();
            if (left <= room) return new OffsetDateTime[] {start, begin.plusSeconds(left)};
            left -= room;
        }
        return null;
    }

    static int minutes(SequenceSnapshotResponse snapshot, String from, String to) {
        Map<String, Integer> next = snapshot.changeovers().get(from);
        Integer minutes = next == null ? null : next.get(to);
        return minutes == null ? 0 : minutes;
    }

    private static BigDecimal weight(SequenceSnapshotResponse snapshot, String priority) {
        BigDecimal weight = snapshot.weights().lateMinute().get(priority == null ? "normal" : priority);
        return weight == null ? snapshot.weights().lateMinute().getOrDefault("normal", BigDecimal.ONE) : weight;
    }

    private static long ceilMinutes(long seconds) {
        return Math.floorDiv(seconds + 59, 60);
    }
}
