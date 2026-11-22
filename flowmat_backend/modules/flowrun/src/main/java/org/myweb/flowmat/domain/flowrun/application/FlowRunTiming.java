package org.myweb.flowmat.domain.flowrun.application;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretches;

/**
 * When a step's input is ready and when it is scheduled, from times and working time alone: the rules the router follows
 * (docs/architecture/adr/ADR-010-connection-delay.md, ADR-015, ADR-016, ADR-022), kept here so the try-it schedule
 * (docs/architecture/adr/ADR-041-try-it-time.md) answers with the same ones. Times are cut to microseconds, as stored.
 */
final class FlowRunTiming {

    private FlowRunTiming() {
    }

    /** What the work order's working time says, asked only when a delay or a wait needs it. */
    interface WorkingTime {
        /** When {@code working} of working time has passed since {@code from}; null to count all time instead. */
        OffsetDateTime afterWorking(OffsetDateTime from, Duration working);

        /** The next working start when {@code readyAt} falls outside working time; null to keep {@code readyAt}. */
        LinkedRunWorkingTime.WorkingStart workingStart(OffsetDateTime readyAt);
    }

    /** A run with no work order's time to follow: every delay counts all time and nothing waits for a shift. */
    static final WorkingTime ALL_TIME = new WorkingTime() {
        @Override
        public OffsetDateTime afterWorking(OffsetDateTime from, Duration working) {
            return null;
        }

        @Override
        public LinkedRunWorkingTime.WorkingStart workingStart(OffsetDateTime readyAt) {
            return null;
        }
    };

    /**
     * Working time from the stretches a work order's equipment and crew work together, as production finds them: a delay that
     * counts working time ends once they have worked that long (all time when the stretches hold less), and a step ready
     * outside them waits for the next one, held by {@code equipmentId} and {@code crew}.
     */
    static WorkingTime stretches(List<WorkingStretch> stretches, String equipmentId, List<String> crew) {
        return new WorkingTime() {
            @Override
            public OffsetDateTime afterWorking(OffsetDateTime from, Duration working) {
                Instant end = WorkingStretches.afterWorking(stretches, from.toInstant(), working);
                return end == null ? null : end.atOffset(from.getOffset());
            }

            @Override
            public LinkedRunWorkingTime.WorkingStart workingStart(OffsetDateTime readyAt) {
                Instant start = WorkingStretches.firstStart(stretches, readyAt.toInstant());
                return start == null || start.equals(readyAt.toInstant()) ? null
                    : new LinkedRunWorkingTime.WorkingStart(start.atOffset(readyAt.getOffset()), equipmentId, crew);
            }
        };
    }

    /**
     * When what came along a connection is ready (ADR-010 decisions 2-4): the source step's end, or for a gateway the time it
     * received what it passed on ({@code carried}), plus the connection's delay. A delay that counts working time ends once
     * that much working time has passed (ADR-022 decision 2), or as an all-time delay without it. Null when no delay applies
     * on the way, so the step can start at once.
     */
    static OffsetDateTime readyAt(OffsetDateTime ended, OffsetDateTime carried, Duration delay, boolean workingDelay,
        WorkingTime time) {
        OffsetDateTime base = carried != null ? carried : ended;
        if (base == null || delay == null && carried == null) {
            return null;
        }
        if (delay == null) {
            return base.truncatedTo(ChronoUnit.MICROS);
        }
        OffsetDateTime worked = workingDelay ? time.afterWorking(base, delay) : null;
        return (worked != null ? worked : base.plus(delay)).truncatedTo(ChronoUnit.MICROS);
    }

    /** When a step whose input is ready at {@code readyAt} is scheduled, and what held it, if anything. */
    record Scheduled(OffsetDateTime at, LinkedRunWorkingTime.WorkingStart held) {
    }

    /**
     * Not before its input is ready (ADR-010 decisions 1, 6), and not outside the work order's working time (ADR-015
     * decision 5, ADR-016 decision 5); a gateway keeps the time it received it.
     */
    static Scheduled scheduled(OffsetDateTime readyAt, boolean gateway, WorkingTime time) {
        LinkedRunWorkingTime.WorkingStart held = gateway ? null : time.workingStart(readyAt);
        OffsetDateTime at = held == null ? readyAt
            : held.at().withOffsetSameInstant(readyAt.getOffset()).truncatedTo(ChronoUnit.MICROS);
        return new Scheduled(at, held);
    }

    /** One arrival at a Join: when what it brought was ready (null without a delay) and when its source step ended. */
    record Arrival(OffsetDateTime readyAt, OffsetDateTime ended) {
    }

    /** A Join's arrivals' latest ready time once a delay applies to any of them (ADR-010 decision 4); else null. */
    static OffsetDateTime joinReadyAt(List<Arrival> arrivals) {
        OffsetDateTime latest = null;
        boolean delayed = false;
        for (Arrival arrival : arrivals) {
            delayed |= arrival.readyAt() != null;
            OffsetDateTime at = arrival.readyAt() != null ? arrival.readyAt() : arrival.ended();
            if (at != null && (latest == null || at.isAfter(latest))) {
                latest = at;
            }
        }
        return delayed && latest != null ? latest.truncatedTo(ChronoUnit.MICROS) : null;
    }
}
