package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.RunLaborCostResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborCostResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrectionLine;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunLabor;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunCorrectionLineRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunLaborRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRateQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRates;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labor cost of runs and work orders, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md): each
 * record that counts is its minutes × the worker's rate on the day it was recorded / 60, or on the run's original end for
 * time a correction added (as equipment AS8). Nothing is stored; the worked-time answers without amounts stay as they are.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunLaborCostService {
    private static final String NOT_DELETED = "N";
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(4);

    private final ProductionRunRepository runs;
    private final WorkOrderRepository workOrders;
    private final ProductionRunLaborRepository labor;
    private final ProductionRunCorrectionLineRepository correctionLines;
    private final WorkerLaborRateQuery rates;
    private final ProjectCalendarQuery calendars;
    private final ProjectAccessService access;

    public RunLaborCostResponse run(String runId) {
        ProductionRun run = runs.findByProductionRunIdAndDeletedYn(runId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(run.getProjectId());
        Priced priced = price(run.getProjectId(), List.of(run));
        return new RunLaborCostResponse(runId, priced.minutes(), priced.cost(), priced.complete(), priced.people(), priced.lines());
    }

    public WorkOrderLaborCostResponse workOrder(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(order.getProjectId());
        // The same runs as the worked-time comparison: physical ones (LB6).
        List<ProductionRun> physical = runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED).stream()
            .filter(ProductionRun::affectsPhysicalState)
            .toList();
        Priced priced = price(order.getProjectId(), physical);
        return new WorkOrderLaborCostResponse(workOrderId, physical.size(), priced.minutes(), priced.cost(), priced.complete(),
            priced.people());
    }

    private record Priced(int minutes, BigDecimal cost, boolean complete, List<RunLaborCostResponse.Person> people,
                          List<RunLaborCostResponse.Line> lines) {
    }

    private Priced price(String projectId, List<ProductionRun> priced) {
        if (priced.isEmpty()) {
            return new Priced(0, NONE, true, List.of(), List.of());
        }
        Map<String, ProductionRun> byId = priced.stream().collect(Collectors.toMap(ProductionRun::getProductionRunId, Function.identity()));
        List<ProductionRunLabor> rows = labor.findAllByProductionRunIdInAndCancelledYnOrderByRecordedAtAscRunLaborIdAsc(byId.keySet(), "N");
        if (rows.isEmpty()) {
            return new Priced(0, NONE, true, List.of(), List.of());
        }
        Set<String> corrected = correctionLines.findAllByCreatedRunLaborIdIn(rows.stream().map(ProductionRunLabor::getRunLaborId).toList())
            .stream().map(ProductionRunCorrectionLine::getCreatedRunLaborId).collect(Collectors.toSet());
        WorkerLaborRates known = rates.rates(projectId, rows.stream().map(ProductionRunLabor::getUserId).distinct().toList());
        List<RunLaborCostResponse.Line> lines = new ArrayList<>();
        Map<String, int[]> minutes = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        Map<String, Boolean> complete = new LinkedHashMap<>();
        // One zone lookup for every record's project date.
        ZoneId zone = calendars.zone(projectId);
        for (ProductionRunLabor row : rows) {
            ProductionRun run = byId.get(row.getProductionRunId());
            boolean byCorrection = corrected.contains(row.getRunLaborId()) && run.getActualEndAt() != null;
            LocalDate day = (byCorrection ? run.getActualEndAt() : row.getRecordedAt()).atZoneSameInstant(zone).toLocalDate();
            Optional<BigDecimal> rate = known.on(row.getUserId(), day);
            BigDecimal cost = rate.map(perHour -> perHour.multiply(BigDecimal.valueOf(row.getWorkedMinutes()))
                .divide(MINUTES_PER_HOUR, 4, RoundingMode.HALF_UP)).orElse(null);
            lines.add(new RunLaborCostResponse.Line(row.getRunLaborId(), row.getUserId(), row.getWorkedMinutes(), day,
                byCorrection ? "run_end" : "recorded", rate.orElse(null), cost));
            minutes.computeIfAbsent(row.getUserId(), user -> new int[1])[0] += row.getWorkedMinutes();
            costs.merge(row.getUserId(), cost == null ? NONE : cost, BigDecimal::add);
            complete.merge(row.getUserId(), cost != null, Boolean::logicalAnd);
        }
        List<RunLaborCostResponse.Person> people = minutes.keySet().stream()
            .map(user -> new RunLaborCostResponse.Person(user, minutes.get(user)[0], costs.get(user), complete.get(user)))
            .toList();
        return new Priced(people.stream().mapToInt(RunLaborCostResponse.Person::workedMinutes).sum(),
            people.stream().map(RunLaborCostResponse.Person::cost).reduce(NONE, BigDecimal::add),
            people.stream().allMatch(RunLaborCostResponse.Person::costComplete), people, lines);
    }
}
