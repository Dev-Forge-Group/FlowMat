package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A worker's working time in a window (docs/domain/worker-calendar.md WC17): their shift time less days off and the project's
 * holidays, as merged spans in time order. {@code calendarSet} is false, with no spans, when they have no shifts.
 */
public record WorkerTime(boolean calendarSet, List<Span> spans) {

    public record Span(Instant start, Instant end) {
    }

    /** Seconds of working time in the whole window. */
    public long seconds() {
        return spans.stream().mapToLong(span -> Duration.between(span.start(), span.end()).getSeconds()).sum();
    }

    /** Seconds of working time in [from, to). */
    public long secondsBetween(Instant from, Instant to) {
        long seconds = 0;
        for (Span span : spans) {
            Instant start = span.start().isAfter(from) ? span.start() : from;
            Instant end = span.end().isBefore(to) ? span.end() : to;
            if (end.isAfter(start)) {
                seconds += Duration.between(start, end).getSeconds();
            }
        }
        return seconds;
    }
}
