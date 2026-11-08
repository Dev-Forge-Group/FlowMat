package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRateQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRates;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerPayQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labor cost of runs and work orders, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md): each
 * record that counts is its minutes × the worker's rate on the day it was recorded / 60, or on the run's original end for
 * time a correction added (as equipment AS8). With the pay premiums each record also costs its worker's pay over attendance on
 * that day (docs/architecture/adr/ADR-028-pay-premiums.md decision 10), or with clock times the premiums in force at those times
 * (decisions 24-25). Nothing is stored; the worked-time answers without amounts stay as they are.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunLaborCostService {
    private static final String NOT_DELETED = "N";
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final BigDecimal NONE = BigDecimal.ZERO.setScale(4);
    private static final long HUNDRED = 100L;

    private final ProductionRunRepository runs;
    private final WorkOrderRepository workOrders;
    private final ProductionRunLaborRepository labor;
    private final ProductionRunCorrectionLineRepository correctionLines;
    private final WorkerLaborRateQuery rates;
    private final WorkerPayQuery pay;
    private final ProjectCalendarQuery calendars;
    private final ProjectAccess access;

    public RunLaborCostResponse run(String runId) {
        ProductionRun run = runs.findByProductionRunIdAndDeletedYn(runId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(run.getProjectId());
        Priced priced = price(run.getProjectId(), List.of(run));
        return new RunLaborCostResponse(runId, priced.minutes(), priced.cost(), priced.complete(), priced.people(), priced.lines(),
            priced.premiumCost(), priced.premiumsComplete());
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
            priced.people(), priced.premiumCost(), priced.premiumsComplete());
    }

    private record Priced(int minutes, BigDecimal cost, boolean complete, List<RunLaborCostResponse.Person> people,
                          List<RunLaborCostResponse.Line> lines, BigDecimal premiumCost, boolean premiumsComplete) {
    }

    private Priced price(String projectId, List<ProductionRun> priced) {
        if (priced.isEmpty()) {
            return new Priced(0, NONE, true, List.of(), List.of(), NONE, true);
        }
        Map<String, ProductionRun> byId = priced.stream().collect(Collectors.toMap(ProductionRun::getProductionRunId, Function.identity()));
        List<ProductionRunLabor> rows = labor.findAllByProductionRunIdInAndCancelledYnOrderByRecordedAtAscRunLaborIdAsc(byId.keySet(), "N");
        if (rows.isEmpty()) {
            return new Priced(0, NONE, true, List.of(), List.of(), NONE, true);
        }
        Set<String> corrected = correctionLines.findAllByCreatedRunLaborIdIn(rows.stream().map(ProductionRunLabor::getRunLaborId).toList())
            .stream().map(ProductionRunCorrectionLine::getCreatedRunLaborId).collect(Collectors.toSet());
        WorkerLaborRates known = rates.rates(projectId, rows.stream().map(ProductionRunLabor::getUserId).distinct().toList());
        List<RunLaborCostResponse.Line> lines = new ArrayList<>();
        Map<String, int[]> minutes = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        Map<String, Boolean> complete = new LinkedHashMap<>();
        Map<String, BigDecimal> premiumCosts = new LinkedHashMap<>();
        Map<String, Boolean> premiumsComplete = new LinkedHashMap<>();
        // One zone lookup for every record's project date.
        ZoneId zone = calendars.zone(projectId);
        Map<String, LocalDate> days = new HashMap<>();
        Set<String> byCorrection = new HashSet<>();
        for (ProductionRunLabor row : rows) {
            ProductionRun run = byId.get(row.getProductionRunId());
            boolean corrects = corrected.contains(row.getRunLaborId()) && run.getActualEndAt() != null;
            if (corrects) {
                byCorrection.add(row.getRunLaborId());
            }
            // Worked time with clock times is priced on the day it started (ADR-028 decision 24).
            OffsetDateTime at = row.getWorkedFrom() != null ? row.getWorkedFrom() : corrects ? run.getActualEndAt() : row.getRecordedAt();
            days.put(row.getRunLaborId(), at.atZoneSameInstant(zone).toLocalDate());
        }
        Map<String, Map<LocalDate, WorkerPayQuery.PaidDay>> paid = paidDays(projectId, rows, days);
        Map<String, Timing> timing = timing(projectId, rows, days, zone);
        for (ProductionRunLabor row : rows) {
            LocalDate day = days.get(row.getRunLaborId());
            Optional<BigDecimal> rate = known.on(row.getUserId(), day);
            BigDecimal cost = rate.map(perHour -> perHour.multiply(BigDecimal.valueOf(row.getWorkedMinutes()))
                .divide(MINUTES_PER_HOUR, 4, RoundingMode.HALF_UP)).orElse(null);
            Premium premium = premium(row, day, paid.get(row.getUserId()).get(day), timing.get(row.getUserId()));
            BigDecimal premiumCost = cost == null || premium.factor() == null ? cost
                : rate.get().multiply(BigDecimal.valueOf(row.getWorkedMinutes())).multiply(premium.paid())
                    .divide(MINUTES_PER_HOUR.multiply(premium.attended()), 4, RoundingMode.HALF_UP);
            lines.add(new RunLaborCostResponse.Line(row.getRunLaborId(), row.getUserId(), row.getWorkedMinutes(), day,
                row.getWorkedFrom() != null ? "worked" : byCorrection.contains(row.getRunLaborId()) ? "run_end" : "recorded",
                rate.orElse(null), cost, premium.factor(), premiumCost, row.getWorkedFrom(), row.getWorkedTo()));
            minutes.computeIfAbsent(row.getUserId(), user -> new int[1])[0] += row.getWorkedMinutes();
            costs.merge(row.getUserId(), cost == null ? NONE : cost, BigDecimal::add);
            complete.merge(row.getUserId(), cost != null, Boolean::logicalAnd);
            premiumCosts.merge(row.getUserId(), premiumCost == null ? NONE : premiumCost, BigDecimal::add);
            premiumsComplete.merge(row.getUserId(), cost == null || premium.known(), Boolean::logicalAnd);
        }
        List<RunLaborCostResponse.Person> people = minutes.keySet().stream()
            .map(user -> new RunLaborCostResponse.Person(user, minutes.get(user)[0], costs.get(user), complete.get(user),
                premiumCosts.get(user), premiumsComplete.get(user)))
            .toList();
        return new Priced(people.stream().mapToInt(RunLaborCostResponse.Person::workedMinutes).sum(),
            people.stream().map(RunLaborCostResponse.Person::cost).reduce(NONE, BigDecimal::add),
            people.stream().allMatch(RunLaborCostResponse.Person::costComplete), people, lines,
            people.stream().map(RunLaborCostResponse.Person::premiumCost).reduce(NONE, BigDecimal::add),
            people.stream().allMatch(RunLaborCostResponse.Person::premiumsComplete));
    }

    /** Each worker's attendance as pay over the days of their records (ADR-028 decision 10). */
    private Map<String, Map<LocalDate, WorkerPayQuery.PaidDay>> paidDays(String projectId, List<ProductionRunLabor> rows,
                                                                         Map<String, LocalDate> days) {
        Map<String, List<LocalDate>> byWorker = rows.stream().collect(Collectors.groupingBy(ProductionRunLabor::getUserId,
            Collectors.mapping(row -> days.get(row.getRunLaborId()), Collectors.toList())));
        Map<String, Map<LocalDate, WorkerPayQuery.PaidDay>> paid = new HashMap<>();
        byWorker.forEach((user, dates) -> paid.put(user, pay.paidDays(projectId, user, Collections.min(dates), Collections.max(dates))));
        return paid;
    }

    /** A worker's attended time cut by premium, and the clock times of their timed records in the project (decisions 24-25). */
    private record Timing(List<WorkerPayQuery.PremiumSpan> spans, List<Instant[]> timed) {
    }

    /**
     * For each worker with timed records around the priced days: their premium spans from the day before the first priced day through
     * the day after the last, and their timed records that count in the project started then. None for a worker without any.
     */
    private Map<String, Timing> timing(String projectId, List<ProductionRunLabor> rows, Map<String, LocalDate> days, ZoneId zone) {
        Map<String, List<ProductionRunLabor>> byWorker = rows.stream().collect(Collectors.groupingBy(ProductionRunLabor::getUserId));
        Map<String, Timing> timing = new HashMap<>();
        byWorker.forEach((user, own) -> {
            LocalDate first = own.stream().map(row -> days.get(row.getRunLaborId())).min(Comparator.naturalOrder()).orElseThrow()
                .minusDays(1);
            LocalDate last = own.stream().map(row -> row.getWorkedTo() != null ? row.getWorkedTo().atZoneSameInstant(zone).toLocalDate()
                : days.get(row.getRunLaborId())).max(Comparator.naturalOrder()).orElseThrow().plusDays(1);
            List<Instant[]> timed = labor.findAllByProjectIdAndUserIdAndCancelledYnAndWorkedFromGreaterThanEqualAndWorkedFromLessThan(
                    projectId, user, "N", first.atStartOfDay(zone).toOffsetDateTime(), last.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
                .stream().map(row -> new Instant[] {row.getWorkedFrom().toInstant(), row.getWorkedTo().toInstant()}).toList();
            if (!timed.isEmpty()) {
                timing.put(user, new Timing(pay.premiumSpans(projectId, user, first, last), timed));
            }
        });
        return timing;
    }

    /** A record's paid over attended time, their ratio (null when not known) and whether its premiums are known. */
    private record Premium(BigDecimal paid, BigDecimal attended, BigDecimal factor, boolean known) {
        static final Premium UNKNOWN = new Premium(null, null, null, false);
    }

    /**
     * A record's premiums. With clock times its own minutes take the premiums in force then, time outside attendance at the rate
     * (decision 24). Without, the day's pay over its attendance (decision 10), less what the timed records took of it (decision 25).
     */
    private static Premium premium(ProductionRunLabor row, LocalDate day, WorkerPayQuery.PaidDay attended, Timing timing) {
        if (row.getWorkedFrom() != null) {
            Instant from = row.getWorkedFrom().toInstant();
            Instant to = row.getWorkedTo().toInstant();
            long span = Duration.between(from, to).getSeconds();
            long[] own = share(timing == null ? List.of() : timing.spans(), from, to);
            if (own[0] == 0) {
                return Premium.UNKNOWN;
            }
            BigDecimal paid = BigDecimal.valueOf(own[1] + HUNDRED * (span - own[0]));
            BigDecimal whole = BigDecimal.valueOf(HUNDRED * span);
            return new Premium(paid, whole, paid.divide(whole, 4, RoundingMode.HALF_UP), own[0] == span);
        }
        if (attended == null) {
            return Premium.UNKNOWN;
        }
        BigDecimal paid = attended.paidMinutes();
        BigDecimal minutes = BigDecimal.valueOf(attended.attendedMinutes());
        if (timing != null) {
            long[] taken = covered(timing, day);
            long rest = attended.attendedMinutes() * 60L - taken[0];
            if (taken[0] > 0 && rest > 0) {
                paid = attended.paidMinutes().multiply(BigDecimal.valueOf(60L * HUNDRED)).subtract(BigDecimal.valueOf(taken[1]));
                minutes = BigDecimal.valueOf(HUNDRED * rest);
            }
        }
        return new Premium(paid, minutes, paid.divide(minutes, 4, RoundingMode.HALF_UP), true);
    }

    /** {attended seconds, those seconds times 100 plus the percent added} of [from, to) in the spans. */
    private static long[] share(List<WorkerPayQuery.PremiumSpan> spans, Instant from, Instant to) {
        long[] share = new long[2];
        for (WorkerPayQuery.PremiumSpan span : spans) {
            Instant start = span.from().isAfter(from) ? span.from() : from;
            Instant end = span.to().isBefore(to) ? span.to() : to;
            if (end.isAfter(start)) {
                long seconds = Duration.between(start, end).getSeconds();
                share[0] += seconds;
                share[1] += seconds * (HUNDRED + span.percent());
            }
        }
        return share;
    }

    /** The same over the work day's attendance that its worker's timed records cover, each second once (decision 25). */
    private static long[] covered(Timing timing, LocalDate day) {
        List<WorkerPayQuery.PremiumSpan> spans = timing.spans().stream().filter(span -> span.workDate().equals(day)).toList();
        List<Instant[]> sorted = timing.timed().stream().sorted(Comparator.comparing((Instant[] one) -> one[0])).toList();
        long[] covered = new long[2];
        Instant from = null;
        Instant to = null;
        for (Instant[] one : sorted) {
            if (to != null && !one[0].isAfter(to)) {
                to = one[1].isAfter(to) ? one[1] : to;
                continue;
            }
            if (to != null) {
                add(covered, share(spans, from, to));
            }
            from = one[0];
            to = one[1];
        }
        if (to != null) {
            add(covered, share(spans, from, to));
        }
        return covered;
    }

    private static void add(long[] sum, long[] one) {
        sum[0] += one[0];
        sum[1] += one[1];
    }
}
