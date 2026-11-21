package org.myweb.flowmat.domain.flowrun.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;

/** The router's timing rules on their own (docs/architecture/adr/ADR-041-try-it-time.md decision 4). */
class FlowRunTimingTest {

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse("2026-12-21T" + time + "+09:00");
    }

    private static WorkingStretch stretch(String start, String end) {
        return new WorkingStretch(at(start).toInstant(), at(end).toInstant());
    }

    /** A press and kim work 08:00–12:00 and 13:00–17:00 (+09:00). */
    private static final FlowRunTiming.WorkingTime DAY = FlowRunTiming.stretches(
        List.of(stretch("08:00:00", "12:00:00"), stretch("13:00:00", "17:00:00")), "press", List.of("kim"));

    @Test
    void withoutADelayAStepIsReadyAtOnce() {
        assertThat(FlowRunTiming.readyAt(at("09:00:00"), null, null, false, DAY)).isNull();
        // Nothing has ended yet.
        assertThat(FlowRunTiming.readyAt(null, null, Duration.ofMinutes(5), false, DAY)).isNull();
    }

    @Test
    void aDelayCountsFromTheEndOrFromWhatAGatewayCarried() {
        assertThat(FlowRunTiming.readyAt(at("09:00:00"), null, Duration.ofMinutes(30), false, DAY)).isEqualTo(at("09:30:00"));
        assertThat(FlowRunTiming.readyAt(at("09:00:00"), at("10:00:00"), Duration.ofMinutes(30), false, DAY))
            .isEqualTo(at("10:30:00"));
        // A gateway's carried time goes on even without a delay of its own.
        assertThat(FlowRunTiming.readyAt(at("09:00:00"), at("10:00:00"), null, false, DAY)).isEqualTo(at("10:00:00"));
    }

    @Test
    void aWorkingTimeDelaySkipsTheBreakAndAllTimeIsTheFallback() {
        assertThat(FlowRunTiming.readyAt(at("11:00:00"), null, Duration.ofHours(2), true, DAY)).isEqualTo(at("14:00:00"));
        // More working time than the stretches hold: counted as all time, as for a run without a work order.
        assertThat(FlowRunTiming.readyAt(at("11:00:00"), null, Duration.ofHours(9), true, DAY)).isEqualTo(at("20:00:00"));
        assertThat(FlowRunTiming.readyAt(at("11:00:00"), null, Duration.ofHours(2), true, FlowRunTiming.ALL_TIME))
            .isEqualTo(at("13:00:00"));
    }

    @Test
    void timesAreCutToMicroseconds() {
        assertThat(FlowRunTiming.readyAt(at("09:00:00"), null, Duration.ofNanos(1_999), false, DAY))
            .isEqualTo(at("09:00:00.000001"));
    }

    @Test
    void aStepReadyOutsideWorkingTimeWaitsAndSaysWhatHeldIt() {
        FlowRunTiming.Scheduled waits = FlowRunTiming.scheduled(at("12:15:00"), false, DAY);
        assertThat(waits.at()).isEqualTo(at("13:00:00"));
        assertThat(waits.held()).isEqualTo(new LinkedRunWorkingTime.WorkingStart(at("13:00:00"), "press", List.of("kim")));
        FlowRunTiming.Scheduled atOnce = FlowRunTiming.scheduled(at("09:15:00"), false, DAY);
        assertThat(atOnce.at()).isEqualTo(at("09:15:00"));
        assertThat(atOnce.held()).isNull();
    }

    @Test
    void aGatewayKeepsTheTimeItReceivedAndNothingWaitsBeyondTheStretches() {
        assertThat(FlowRunTiming.scheduled(at("12:15:00"), true, DAY)).isEqualTo(new FlowRunTiming.Scheduled(at("12:15:00"), null));
        assertThat(FlowRunTiming.scheduled(at("18:00:00"), false, DAY).held()).isNull();
    }

    @Test
    void theWaitKeepsTheReadyTimesOffset() {
        OffsetDateTime utc = at("12:15:00").withOffsetSameInstant(java.time.ZoneOffset.UTC);
        assertThat(FlowRunTiming.scheduled(utc, false, DAY).at()).isEqualTo(at("13:00:00").withOffsetSameInstant(java.time.ZoneOffset.UTC))
            .hasToString("2026-12-21T04:00Z");
    }

    @Test
    void aJoinIsReadyWhenItsLatestArrivalIs() {
        assertThat(FlowRunTiming.joinReadyAt(List.of(new FlowRunTiming.Arrival(at("10:00:00"), at("09:00:00")),
            new FlowRunTiming.Arrival(null, at("11:00:00"))))).isEqualTo(at("11:00:00"));
        // Without a delay on any arrival the Join takes no time of its own.
        assertThat(FlowRunTiming.joinReadyAt(List.of(new FlowRunTiming.Arrival(null, at("09:00:00"))))).isNull();
        assertThat(FlowRunTiming.joinReadyAt(List.of())).isNull();
    }

    @Test
    void stretchesAnswerAsTheProductionLookupDoes() {
        Instant noon = at("12:00:00").toInstant();
        assertThat(DAY.afterWorking(at("10:00:00"), Duration.ofHours(2)).toInstant()).isEqualTo(noon);
        assertThat(DAY.workingStart(at("12:00:00")).at().toInstant()).isEqualTo(at("13:00:00").toInstant());
    }
}
