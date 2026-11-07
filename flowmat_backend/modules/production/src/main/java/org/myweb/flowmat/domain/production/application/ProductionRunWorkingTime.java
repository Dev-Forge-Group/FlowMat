package org.myweb.flowmat.domain.production.application;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWorkingTimeQuery;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a production run's delayed step can start (docs/architecture/adr/ADR-015-shift-aware-delay.md,
 * docs/architecture/adr/ADR-016-crew-aware-delay.md): the first time at or after the delay's end when the work order's
 * equipment is available and everyone on its crew with a full share and shifts is working, the people plan suggestions
 * follow (docs/domain/worker-calendar.md WC24, WC39). A run without a work order, or one with neither equipment nor such
 * people, keeps the time the delay ends. A delay that counts only working time ends once they have worked that long together
 * (docs/architecture/adr/ADR-022-working-time-delay.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunWorkingTime implements LinkedRunWorkingTime {

    /** How far ahead to look, nearest first; the last is as far as the equipment's own search goes. */
    private static final List<Duration> AHEAD = List.of(Duration.ofDays(7), Duration.ofDays(31), Duration.ofDays(366));

    private final ProductionRunRepository productionRunRepository;
    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderCrewRepository crewRepository;
    private final EquipmentWorkingTimeQuery equipmentWorkingTime;
    private final WorkerCalendarQuery workerCalendars;

    @Override
    public Optional<WorkingStart> workingStart(String productionRunId, OffsetDateTime readyAt) {
        Team team = team(productionRunId);
        if (team == null) {
            return Optional.empty();
        }
        for (Duration ahead : AHEAD) {
            Together together = together(team, readyAt, readyAt.plus(ahead));
            if (together == null) {
                return Optional.empty();
            }
            if (!together.spans().isEmpty()) {
                Instant start = together.spans().get(0).start();
                return start.equals(readyAt.toInstant()) ? Optional.empty()
                    : Optional.of(new WorkingStart(start.atOffset(readyAt.getOffset()), team.equipmentId(), together.crew()));
            }
        }
        return Optional.empty();
    }

    /**
     * The same stretches added up from {@code from} until {@code working} has passed (ADR-022 decision 2); the end of the last
     * one when it fills it exactly, so the step then waits for the next working time as any delay does.
     */
    @Override
    public Optional<WorkingStart> afterWorking(String productionRunId, OffsetDateTime from, Duration working) {
        Team team = team(productionRunId);
        if (team == null) {
            return Optional.empty();
        }
        for (Duration ahead : AHEAD) {
            Together together = together(team, from, from.plus(ahead));
            if (together == null) {
                return Optional.empty();
            }
            Duration left = working;
            for (WorkerTime.Span span : together.spans()) {
                Instant start = span.start().isBefore(from.toInstant()) ? from.toInstant() : span.start();
                Duration length = Duration.between(start, span.end());
                if (length.compareTo(left) >= 0) {
                    return Optional.of(new WorkingStart(start.plus(left).atOffset(from.getOffset()), team.equipmentId(),
                        together.crew()));
                }
                left = left.minus(length);
            }
        }
        return Optional.empty();
    }

    /** The work order's equipment and the people needed for all of their time (WC39, ADR-016 decision 3). */
    private record Team(String projectId, String equipmentId, List<String> fullCrew) {
    }

    /** When the team works together in a window, earliest first, and the crew with shifts that shaped it. */
    private record Together(List<WorkerTime.Span> spans, List<String> crew) {
    }

    /** Null without a work order, or with neither equipment nor full-share crew. */
    private Team team(String productionRunId) {
        WorkOrder order = productionRunRepository.findById(productionRunId)
            .map(ProductionRun::getWorkOrderId)
            .flatMap(workOrderRepository::findById)
            .orElse(null);
        if (order == null) {
            return null;
        }
        String equipmentId = order.getEquipmentId();
        // Only people needed for all of their time fix when the step can start (WC39, ADR-016 decision 3).
        List<String> fullCrew = crewRepository.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(order.getWorkOrderId()).stream()
            .filter(member -> member.getSharePercent() == null || member.getSharePercent() == WorkOrderCrewService.FULL_SHARE)
            .map(WorkOrderCrew::getUserId)
            .toList();
        return equipmentId == null && fullCrew.isEmpty() ? null : new Team(order.getProjectId(), equipmentId, fullCrew);
    }

    /** [from, until) for the team; null when neither its equipment nor anyone with shifts fixes it. */
    private Together together(Team team, OffsetDateTime from, OffsetDateTime until) {
        Map<String, WorkerTime> time = team.fullCrew().isEmpty() ? Map.of()
            : workerCalendars.workingTime(team.projectId(), team.fullCrew(), from, until);
        // Without shifts a person's time is not known, so it does not fix the start (WC17).
        List<String> shiftCrew = team.fullCrew().stream()
            .filter(userId -> time.containsKey(userId) && time.get(userId).calendarSet())
            .toList();
        if (team.equipmentId() == null && shiftCrew.isEmpty()) {
            return null;
        }
        return new Together(commonSpans(team.equipmentId(), shiftCrew.stream().map(time::get).toList(), from.toInstant(),
            until.toInstant()), shiftCrew);
    }

    /**
     * The stretches of [from, until) when the equipment (when there is one) may start a step and every one of {@code crew}
     * works: the equipment's stretches for the window, worked out once, intersected with each worker's spans, as plan
     * suggestions do (WC24). The whole window is searched however often the times miss each other (ADR-016 decision 4). Empty
     * when they never meet before {@code until}, or the equipment is gone.
     */
    private List<WorkerTime.Span> commonSpans(String equipmentId, List<WorkerTime> crew, Instant from, Instant until) {
        List<WorkerTime.Span> together;
        if (equipmentId == null) {
            together = List.of(new WorkerTime.Span(from, until));
        } else {
            Optional<List<EquipmentSlot>> stretches = equipmentWorkingTime.workingStretches(equipmentId, from.atOffset(ZoneOffset.UTC),
                until.atOffset(ZoneOffset.UTC));
            if (stretches.isEmpty()) {
                return List.of();
            }
            together = stretches.get().stream()
                .map(slot -> new WorkerTime.Span(slot.start().toInstant(), slot.end().toInstant()))
                .toList();
        }
        for (WorkerTime time : crew) {
            together = intersect(together, time.spans());
        }
        return together;
    }

    /** Both lists earliest first and none overlapping within each; the stretches in both. */
    private static List<WorkerTime.Span> intersect(List<WorkerTime.Span> one, List<WorkerTime.Span> other) {
        List<WorkerTime.Span> result = new ArrayList<>();
        int left = 0;
        int right = 0;
        while (left < one.size() && right < other.size()) {
            WorkerTime.Span a = one.get(left);
            WorkerTime.Span b = other.get(right);
            Instant start = a.start().isAfter(b.start()) ? a.start() : b.start();
            Instant end = a.end().isBefore(b.end()) ? a.end() : b.end();
            if (end.isAfter(start)) {
                result.add(new WorkerTime.Span(start, end));
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
