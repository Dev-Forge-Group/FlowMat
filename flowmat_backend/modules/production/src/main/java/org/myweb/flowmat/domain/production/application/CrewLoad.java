package org.myweb.flowmat.domain.production.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;

/**
 * How much of a person's time the orders that name them want at once (docs/domain/worker-calendar.md WC38-WC39). Shares add
 * up; a person is booked twice only where they pass 100%. Every held order has its planned start and end.
 */
final class CrewLoad {

    private CrewLoad() {
    }

    /** An order that names the person, with their share of it. */
    record Held(WorkOrder order, int share) {
    }

    /** A stretch where the shares pass 100%, and the held orders active in it. */
    record Over(Instant start, Instant end, List<Held> active) {
    }

    /** The stretches of [from, to) where {@code share} plus the shares of the held orders active then pass 100%. */
    static List<Over> over(int share, List<Held> held, Instant from, Instant to) {
        TreeSet<Instant> cuts = new TreeSet<>(List.of(from, to));
        for (Held one : held) {
            Instant start = one.order().getPlannedStartAt().toInstant();
            Instant end = one.order().getPlannedEndAt().toInstant();
            if (start.isAfter(from) && start.isBefore(to)) {
                cuts.add(start);
            }
            if (end.isAfter(from) && end.isBefore(to)) {
                cuts.add(end);
            }
        }
        List<Over> result = new ArrayList<>();
        Instant previous = null;
        for (Instant cut : cuts) {
            if (previous != null) {
                Instant start = previous;
                List<Held> active = held.stream()
                    .filter(one -> one.order().getPlannedStartAt().toInstant().isBefore(cut)
                        && one.order().getPlannedEndAt().toInstant().isAfter(start))
                    .toList();
                if (!active.isEmpty() && share + active.stream().mapToInt(Held::share).sum() > WorkOrderCrewService.FULL_SHARE) {
                    result.add(new Over(start, cut, active));
                }
            }
            previous = cut;
        }
        return result;
    }

    /**
     * The held orders that, with {@code share} of another order, want more than all of the person's time somewhere in
     * [from, to), each with the person's working seconds then. Someone without shifts has no seconds, but the order counts.
     */
    static Map<WorkOrder, Long> overBooked(int share, List<Held> held, OffsetDateTime from, OffsetDateTime to, WorkerTime time) {
        boolean known = time != null && time.calendarSet();
        Map<WorkOrder, Long> result = new LinkedHashMap<>();
        for (Over over : over(share, held, from.toInstant(), to.toInstant())) {
            long seconds = known ? time.secondsBetween(over.start(), over.end()) : 0;
            if (seconds > 0 || !known) {
                over.active().forEach(one -> result.merge(one.order(), seconds, Long::sum));
            }
        }
        return result;
    }
}
