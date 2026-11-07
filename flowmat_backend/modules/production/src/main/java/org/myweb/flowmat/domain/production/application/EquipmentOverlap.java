package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;

/**
 * How full equipment that runs a limited number of work orders side by side is (docs/domain/equipment.md E16-E18). Plan
 * suggestions and readiness both ask it.
 */
final class EquipmentOverlap {

    private EquipmentOverlap() {
    }

    /**
     * Of {@code planned} (orders on the equipment with a planned window, not the one asked about), those running together
     * at the first moment in [start, end) when {@code limit} of them run, so one more would be too many; none when there
     * is room throughout. Earliest start first.
     */
    static List<WorkOrder> full(List<WorkOrder> planned, OffsetDateTime start, OffsetDateTime end, int limit) {
        List<WorkOrder> overlapping = planned.stream()
            .filter(other -> other.getPlannedStartAt().isBefore(end) && other.getPlannedEndAt().isAfter(start))
            .sorted(Comparator.comparing(WorkOrder::getPlannedStartAt))
            .toList();
        List<OffsetDateTime> moments = Stream.concat(Stream.of(start),
                overlapping.stream().map(WorkOrder::getPlannedStartAt).filter(moment -> moment.isAfter(start)))
            .sorted()
            .toList();
        for (OffsetDateTime moment : moments) {
            List<WorkOrder> running = overlapping.stream()
                .filter(other -> !other.getPlannedStartAt().isAfter(moment) && other.getPlannedEndAt().isAfter(moment))
                .toList();
            if (running.size() >= limit) {
                return running;
            }
        }
        return List.of();
    }

    /** Of {@code full}, the orders that end first: there is room again when they do. */
    static List<WorkOrder> firstToEnd(List<WorkOrder> full) {
        OffsetDateTime first = full.stream().map(WorkOrder::getPlannedEndAt).min(Comparator.naturalOrder()).orElse(null);
        return first == null ? List.of() : full.stream().filter(order -> order.getPlannedEndAt().isEqual(first)).toList();
    }
}
