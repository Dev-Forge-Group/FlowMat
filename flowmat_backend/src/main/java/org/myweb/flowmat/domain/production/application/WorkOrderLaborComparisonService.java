package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunStep;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunSteps;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborComparisonResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunLabor;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.ProductionRunLaborRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A work order's planned crew time beside the time its runs recorded for each person (docs/domain/worker-calendar.md LB6). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderLaborComparisonService {
    private static final String NOT_DELETED = "N";
    private static final BigDecimal MINUTES_OF_A_FULL_SHARE = BigDecimal.valueOf(60L).divide(BigDecimal.valueOf(WorkOrderCrewService.FULL_SHARE));

    private final WorkOrderRepository workOrders;
    private final WorkOrderCrewRepository crew;
    private final ProductionRunRepository runs;
    private final ProductionRunLaborRepository labor;
    private final WorkerCalendarQuery workerCalendars;
    private final LinkedRunSteps linkedSteps;
    private final ProjectAccessService access;

    public WorkOrderLaborComparisonResponse compare(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String projectId = order.getProjectId();
        access.requireProjectReadAccess(projectId);

        // Planned: each person's share of their working time in the planned window, the hours the crew picker shows (WC36).
        List<WorkOrderCrew> team = crew.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(workOrderId);
        OffsetDateTime start = order.getPlannedStartAt();
        OffsetDateTime end = order.getPlannedEndAt();
        boolean window = start != null && end != null && end.isAfter(start)
            && Duration.between(start, end).compareTo(WorkOrderCrewService.LONGEST_WINDOW) <= 0;
        Map<String, WorkerHours> hours = window && !team.isEmpty()
            ? workerCalendars.workingHours(projectId, team.stream().map(WorkOrderCrew::getUserId).toList(), start, end) : Map.of();

        // Actual: what its physical runs recorded, cancelled records left out (LB2-LB3).
        List<String> runIds = runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED).stream()
            .filter(ProductionRun::affectsPhysicalState)
            .map(ProductionRun::getProductionRunId)
            .toList();
        Map<String, Integer> actual = new LinkedHashMap<>();
        int actualMinutes = 0;
        // Which process each step of those runs ran, so time on the same process adds up across runs (LB18).
        Map<String, LinkedRunStep> stepsById = new HashMap<>();
        for (String runId : runIds) {
            linkedSteps.steps(runId).stream().filter(step -> !step.gateway()).forEach(step -> stepsById.put(step.stepId(), step));
        }
        Map<String, WorkOrderLaborComparisonResponse.Step> byProcess = new LinkedHashMap<>();
        int wholeRun = 0;
        if (!runIds.isEmpty()) {
            for (ProductionRunLabor row : labor.findAllByProductionRunIdInAndCancelledYnOrderByRecordedAtAscRunLaborIdAsc(runIds, "N")) {
                actual.merge(row.getUserId(), row.getWorkedMinutes(), Integer::sum);
                actualMinutes += row.getWorkedMinutes();
                LinkedRunStep step = row.getStepId() == null ? null : stepsById.get(row.getStepId());
                if (step == null) {
                    wholeRun += row.getWorkedMinutes();
                } else {
                    byProcess.merge(step.nodeId(), new WorkOrderLaborComparisonResponse.Step(step.nodeId(), step.nodeName(),
                        step.sequenceNo(), row.getWorkedMinutes()), (one, other) -> new WorkOrderLaborComparisonResponse.Step(
                        one.nodeId(), one.nodeName(), earlier(one.sequenceNo(), other.sequenceNo()),
                        one.actualMinutes() + other.actualMinutes()));
                }
            }
        }
        List<WorkOrderLaborComparisonResponse.Step> steps = byProcess.values().stream()
            .sorted(Comparator.comparing(WorkOrderLaborComparisonResponse.Step::sequenceNo,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        Integer plannedMinutes = null;
        boolean complete = true;
        List<WorkOrderLaborComparisonResponse.Person> people = new ArrayList<>();
        for (WorkOrderCrew member : team) {
            int share = WorkOrderCrewService.shareOf(member);
            WorkerHours one = hours.get(member.getUserId());
            Integer planned = one == null || !one.calendarSet() ? null
                : one.hours().multiply(MINUTES_OF_A_FULL_SHARE).multiply(BigDecimal.valueOf(share))
                    .setScale(0, RoundingMode.HALF_UP).intValueExact();
            if (planned == null) {
                complete = false;
            } else {
                plannedMinutes = (plannedMinutes == null ? 0 : plannedMinutes) + planned;
            }
            people.add(new WorkOrderLaborComparisonResponse.Person(member.getUserId(), share, planned,
                actual.getOrDefault(member.getUserId(), 0)));
        }
        actual.forEach((userId, minutes) -> {
            if (team.stream().noneMatch(member -> member.getUserId().equals(userId))) {
                people.add(new WorkOrderLaborComparisonResponse.Person(userId, null, null, minutes));
            }
        });
        return new WorkOrderLaborComparisonResponse(workOrderId, plannedMinutes, complete, runIds.size(), actualMinutes, people,
            steps, wholeRun);
    }

    private static Integer earlier(Integer one, Integer other) {
        return one == null ? other : other == null ? one : Math.min(one, other);
    }
}
