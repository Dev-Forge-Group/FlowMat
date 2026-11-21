package org.myweb.flowmat.domain.production.application;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWorkingTimeQuery;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretches;
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
            Instant start = WorkingStretches.firstStart(together.spans(), readyAt.toInstant());
            if (start != null) {
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
            Instant end = WorkingStretches.afterWorking(together.spans(), from.toInstant(), working);
            if (end != null) {
                return Optional.of(new WorkingStart(end.atOffset(from.getOffset()), team.equipmentId(), together.crew()));
            }
        }
        return Optional.empty();
    }

    /** The work order's equipment and the people needed for all of their time (WC39, ADR-016 decision 3). */
    private record Team(String projectId, String equipmentId, List<String> fullCrew) {
    }

    /** When the team works together in a window, earliest first, and the crew with shifts that shaped it. */
    private record Together(List<WorkingStretch> spans, List<String> crew) {
    }

    /** Null without a work order, or with neither equipment nor full-share crew. */
    private Team team(String productionRunId) {
        return productionRunRepository.findById(productionRunId)
            .map(ProductionRun::getWorkOrderId)
            .flatMap(workOrderRepository::findById)
            .map(this::team)
            .orElse(null);
    }

    /**
     * The stretches of [from, until) when a work order's equipment and its full-share crew with shifts work together, and who
     * fixed them (docs/architecture/adr/ADR-041-try-it-time.md decision 5): what a production run of the order waits for and
     * counts working-time delays by. Empty when nothing fixes the time, so every delay counts all time; no stretches when the
     * team never works together in the window, or its equipment is gone.
     */
    public Optional<TeamTime> workingTime(WorkOrder order, OffsetDateTime from, OffsetDateTime until) {
        Team team = team(order);
        Together together = team == null ? null : together(team, from, until);
        return together == null ? Optional.empty()
            : Optional.of(new TeamTime(team.equipmentId(), together.crew(), together.spans()));
    }

    /** A work order's working time: its equipment (null without one), the crew with shifts it follows, the stretches. */
    public record TeamTime(String equipmentId, List<String> crew, List<WorkingStretch> stretches) {
    }

    private Team team(WorkOrder order) {
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
    private List<WorkingStretch> commonSpans(String equipmentId, List<WorkerTime> crew, Instant from, Instant until) {
        List<WorkingStretch> together;
        if (equipmentId == null) {
            together = List.of(new WorkingStretch(from, until));
        } else {
            Optional<List<EquipmentSlot>> stretches = equipmentWorkingTime.workingStretches(equipmentId, from.atOffset(ZoneOffset.UTC),
                until.atOffset(ZoneOffset.UTC));
            if (stretches.isEmpty()) {
                return List.of();
            }
            together = stretches.get().stream()
                .map(slot -> new WorkingStretch(slot.start().toInstant(), slot.end().toInstant()))
                .toList();
        }
        for (WorkerTime time : crew) {
            together = WorkingStretches.intersect(together, time.spans().stream()
                .map(span -> new WorkingStretch(span.start(), span.end())).toList());
        }
        return together;
    }
}
