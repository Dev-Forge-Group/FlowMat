package org.myweb.flowmat.domain.production.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime.Span;

/**
 * What the stretches a work order's equipment and crew work together decide, without asking for them
 * (docs/architecture/adr/ADR-041-try-it-time.md decision 3): when a step that is ready may start, when a delay that counts
 * working time ends, and the stretches two lists share. {@link ProductionRunWorkingTime} finds the stretches for a window and
 * asks these; the flow kernel answers the same from the stretches the working-time API gives. Stretches are earliest first and
 * do not overlap; a stretch that began before {@code from} counts from {@code from}.
 */
final class WorkingStretches {

    private WorkingStretches() {
    }

    /** The first working moment at or after {@code from}: {@code from} itself inside a stretch, else the next one's start. */
    static Instant firstStart(List<Span> stretches, Instant from) {
        for (Span stretch : stretches) {
            if (stretch.end().isAfter(from)) {
                return stretch.start().isBefore(from) ? from : stretch.start();
            }
        }
        return null;
    }

    /**
     * When {@code working} of working time has passed since {@code from} (ADR-022 decision 2): the end of the last stretch when
     * it fills it exactly, so the step then waits for the next working time as any delay does. Null when the stretches hold
     * less than that.
     */
    static Instant afterWorking(List<Span> stretches, Instant from, Duration working) {
        Duration left = working;
        for (Span stretch : stretches) {
            if (!stretch.end().isAfter(from)) {
                continue;
            }
            Instant start = stretch.start().isBefore(from) ? from : stretch.start();
            Duration length = Duration.between(start, stretch.end());
            if (length.compareTo(left) >= 0) {
                return start.plus(left);
            }
            left = left.minus(length);
        }
        return null;
    }

    /** The stretches in both lists. */
    static List<Span> intersect(List<Span> one, List<Span> other) {
        List<Span> result = new ArrayList<>();
        int left = 0;
        int right = 0;
        while (left < one.size() && right < other.size()) {
            Span a = one.get(left);
            Span b = other.get(right);
            Instant start = a.start().isAfter(b.start()) ? a.start() : b.start();
            Instant end = a.end().isBefore(b.end()) ? a.end() : b.end();
            if (end.isAfter(start)) {
                result.add(new Span(start, end));
            }
            if (a.end().isBefore(b.end())) {
                left++;
            } else {
                right++;
            }
        }
        return result;
    }
}
