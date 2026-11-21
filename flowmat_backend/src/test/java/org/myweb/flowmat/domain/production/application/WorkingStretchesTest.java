package org.myweb.flowmat.domain.production.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime.Span;

/** The working-time answers from stretches alone (docs/architecture/adr/ADR-041-try-it-time.md decision 3). */
class WorkingStretchesTest {

    private static Instant at(String time) {
        return Instant.parse("2026-12-21T" + time + "Z");
    }

    private static Span span(String start, String end) {
        return new Span(at(start), at(end));
    }

    /** Two shifts on one day: 08:00–12:00 and 13:00–17:00. */
    private static final List<Span> DAY = List.of(span("08:00:00", "12:00:00"), span("13:00:00", "17:00:00"));

    @Test
    void aReadyTimeInsideAStretchStartsAtOnce() {
        assertThat(WorkingStretches.firstStart(DAY, at("09:30:00"))).isEqualTo(at("09:30:00"));
        assertThat(WorkingStretches.firstStart(DAY, at("08:00:00"))).isEqualTo(at("08:00:00"));
    }

    @Test
    void aReadyTimeOutsideWaitsForTheNextStretch() {
        assertThat(WorkingStretches.firstStart(DAY, at("06:00:00"))).isEqualTo(at("08:00:00"));
        // A stretch's end is not in it.
        assertThat(WorkingStretches.firstStart(DAY, at("12:00:00"))).isEqualTo(at("13:00:00"));
        assertThat(WorkingStretches.firstStart(DAY, at("12:30:00"))).isEqualTo(at("13:00:00"));
    }

    @Test
    void thereIsNoStartAfterTheLastStretch() {
        assertThat(WorkingStretches.firstStart(DAY, at("17:00:00"))).isNull();
        assertThat(WorkingStretches.firstStart(List.of(), at("09:00:00"))).isNull();
    }

    @Test
    void workingTimeIsCountedFromWithinAStretch() {
        assertThat(WorkingStretches.afterWorking(DAY, at("09:00:00"), Duration.ofMinutes(90))).isEqualTo(at("10:30:00"));
    }

    @Test
    void workingTimeCarriesOverTheBreak() {
        // Two hours before noon, one after the break.
        assertThat(WorkingStretches.afterWorking(DAY, at("10:00:00"), Duration.ofHours(3))).isEqualTo(at("14:00:00"));
        // From before the first shift, the wait before it does not count.
        assertThat(WorkingStretches.afterWorking(DAY, at("06:00:00"), Duration.ofHours(5))).isEqualTo(at("14:00:00"));
    }

    @Test
    void filingAStretchExactlyEndsAtItsEnd() {
        // So the step then waits for the next working time, as any delay that ends at a break does.
        assertThat(WorkingStretches.afterWorking(DAY, at("10:00:00"), Duration.ofHours(2))).isEqualTo(at("12:00:00"));
    }

    @Test
    void notEnoughWorkingTimeHasNoEnd() {
        assertThat(WorkingStretches.afterWorking(DAY, at("10:00:00"), Duration.ofHours(7))).isNull();
        assertThat(WorkingStretches.afterWorking(List.of(), at("10:00:00"), Duration.ofSeconds(1))).isNull();
    }

    @Test
    void fractionsOfASecondAreKept() {
        assertThat(WorkingStretches.afterWorking(DAY, at("11:59:59.750"), Duration.ofMillis(500)))
            .isEqualTo(at("13:00:00.250"));
    }

    @Test
    void theStretchesBothListsShare() {
        List<Span> crew = List.of(span("07:00:00", "09:00:00"), span("11:30:00", "14:00:00"), span("16:59:00", "18:00:00"));
        assertThat(WorkingStretches.intersect(DAY, crew)).containsExactly(span("08:00:00", "09:00:00"),
            span("11:30:00", "12:00:00"), span("13:00:00", "14:00:00"), span("16:59:00", "17:00:00"));
        // Stretches that only touch share nothing.
        assertThat(WorkingStretches.intersect(DAY, List.of(span("12:00:00", "13:00:00")))).isEmpty();
    }
}
