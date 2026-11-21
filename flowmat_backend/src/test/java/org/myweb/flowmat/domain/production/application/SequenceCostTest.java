package org.myweb.flowmat.domain.production.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Baseline;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Job;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Slot;

/** The cost of an order of one equipment's jobs (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md decision 1). */
class SequenceCostTest {

    private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2030-01-07T00:00:00+09:00");

    private static OffsetDateTime at(int day, int hour, int minute) {
        return MONDAY.plusDays(day).withHour(hour).withMinute(minute);
    }

    /** 09:00-17:00 on each of the first {@code days} days. */
    private static List<Slot> shifts(int days) {
        return java.util.stream.IntStream.range(0, days).mapToObj(day -> new Slot(at(day, 9, 0), at(day, 17, 0))).toList();
    }

    private static Job job(String number, String item, long hours, OffsetDateTime plannedEnd, String priority, boolean fixed) {
        return new Job(number, number, item, fixed ? "in_progress" : "approved", fixed, BigDecimal.TEN, hours * 3600, MONDAY, plannedEnd,
            priority);
    }

    private static SequenceSnapshotResponse snapshot(List<Slot> slots, String before, List<Job> jobs) {
        Map<String, Map<String, Integer>> changeovers = Map.of(
            "A", Map.of("A", 0, "B", 30),
            "B", Map.of("A", 45, "B", 0));
        return new SequenceSnapshotResponse(1, "p", "e", MONDAY, MONDAY.plusDays(7), MONDAY.plusDays(28), before, slots, jobs,
            changeovers, SequenceCost.WEIGHTS, List.of(), null, null);
    }

    @Test
    void jobsFollowEachOtherThroughTheShiftsWithTheirChangeovers() {
        SequenceSnapshotResponse snapshot = snapshot(shifts(5), null, List.of(
            job("WO-1", "A", 6, at(0, 17, 0), "normal", false),
            job("WO-2", "B", 4, at(1, 17, 0), "normal", false)));

        Baseline cost = SequenceCost.of(snapshot, List.of("WO-1", "WO-2"));

        // A from Monday 09:00 to 15:00; then 30 min changeover and 4 h of B: 2 h on Monday, 2.5 h on Tuesday.
        assertThat(cost.placements().get(0).start()).isEqualTo(at(0, 9, 0));
        assertThat(cost.placements().get(0).end()).isEqualTo(at(0, 15, 0));
        assertThat(cost.placements().get(1).start()).isEqualTo(at(0, 15, 0));
        assertThat(cost.placements().get(1).end()).isEqualTo(at(1, 11, 30));
        assertThat(cost.changeoverMinutes()).isEqualTo(30);
        assertThat(cost.lateMinutes()).isZero();
        assertThat(cost.objective()).isEqualByComparingTo("30");
    }

    @Test
    void lateMinutesCountByPriorityAndTheItemBeforeCounts() {
        SequenceSnapshotResponse snapshot = snapshot(shifts(5), "B", List.of(
            job("WO-1", "A", 8, at(0, 16, 0), "urgent", false),
            job("WO-2", "B", 1, at(0, 9, 0), "low", false)));

        Baseline cost = SequenceCost.of(snapshot, List.of("WO-1", "WO-2"));

        // B → A 45 min first: A ends Tuesday 09:45, 17 h 45 min past its 16:00 Monday end; B runs after another 30 min.
        assertThat(cost.placements().get(0).end()).isEqualTo(at(1, 9, 45));
        assertThat(cost.placements().get(0).lateMinutes()).isEqualTo(17 * 60 + 45);
        assertThat(cost.placements().get(1).end()).isEqualTo(at(1, 11, 15));
        assertThat(cost.placements().get(1).lateMinutes()).isEqualTo(26 * 60 + 15);
        assertThat(cost.changeoverMinutes()).isEqualTo(75);
        // 75 + 4 × 1065 + 0.5 × 1575
        assertThat(cost.weightedLateMinutes()).isEqualByComparingTo("5047.5");
        assertThat(cost.objective()).isEqualByComparingTo("5122.5");
    }

    @Test
    void aJobThatDoesNotFitLeavesTheOrderWithoutACost() {
        SequenceSnapshotResponse snapshot = snapshot(shifts(1), null, List.of(
            job("WO-1", "A", 6, at(0, 17, 0), "normal", false),
            job("WO-2", "A", 6, at(0, 17, 0), "normal", false)));

        Baseline cost = SequenceCost.of(snapshot, List.of("WO-1", "WO-2"));

        assertThat(cost.unplaced()).isEqualTo(1);
        assertThat(cost.objective()).isNull();
        assertThat(cost.placements().get(1).end()).isNull();
    }

    @Test
    void aJobOfNoTimeBeginsAndEndsAtTheFirstAvailableMoment() {
        SequenceSnapshotResponse snapshot = snapshot(shifts(2), null, List.of(job("WO-1", "A", 0, at(0, 17, 0), "normal", false)));

        Baseline cost = SequenceCost.of(snapshot, List.of("WO-1"));

        assertThat(cost.placements().get(0).start()).isEqualTo(at(0, 9, 0));
        assertThat(cost.placements().get(0).end()).isEqualTo(at(0, 9, 0));
    }

    @Test
    void anOrderNamesEveryJobOnceWithTheRunningOnesFirst() {
        SequenceSnapshotResponse snapshot = snapshot(shifts(5), null, List.of(
            job("WO-1", "A", 1, at(0, 17, 0), "normal", true),
            job("WO-2", "B", 1, at(0, 17, 0), "normal", false)));

        assertThatThrownBy(() -> SequenceCost.of(snapshot, List.of("WO-2", "WO-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SequenceCost.of(snapshot, List.of("WO-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SequenceCost.of(snapshot, List.of("WO-1", "WO-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCurrentRuleIsTheLoadBoardsSuggestion() {
        SequenceSnapshotResponse snapshot = SequenceSnapshotService.withBaselines(snapshot(shifts(5), null, List.of(
            job("WO-1", "A", 1, at(0, 17, 0), "normal", false),
            job("WO-2", "B", 1, at(1, 17, 0), "normal", false),
            job("WO-3", "A", 1, at(2, 17, 0), "normal", false))));

        assertThat(snapshot.plan().order()).containsExactly("WO-1", "WO-2", "WO-3");
        assertThat(snapshot.plan().changeoverMinutes()).isEqualTo(75);
        assertThat(snapshot.currentRule().order()).containsExactly("WO-1", "WO-3", "WO-2");
        assertThat(snapshot.currentRule().changeoverMinutes()).isEqualTo(30);
        assertThat(snapshot.currentRule().objective()).isEqualByComparingTo("30");
    }
}
