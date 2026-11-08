package org.myweb.flowmat.domain.production.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.AttendanceComparisonResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrectionLine;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunLabor;
import org.myweb.flowmat.domain.production.repository.ProductionRunCorrectionLineRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunLaborRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerAttendanceQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A worker's attendance beside the worked time recorded on physical runs, by project date (docs/architecture/adr/ADR-026-attendance.md
 * decision 4). Worked time counts on the day its rate is chosen: the day it was recorded, with clock times the day it started,
 * and time a correction added the run's original end (ADR-025, docs/architecture/adr/ADR-028-pay-premiums.md decisions 27-28).
 * Only the worker and the owner see it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceComparisonService {
    private static final long MAX_RANGE_DAYS = 62;
    private static final long DEFAULT_DAYS = 7;

    private final WorkerAttendanceQuery attendance;
    private final ProductionRunLaborRepository labor;
    private final ProductionRunRepository runs;
    private final ProductionRunCorrectionLineRepository correctionLines;
    private final ProjectAccess access;
    private final ProjectMemberQuery members;
    private final ProjectCalendarQuery calendars;

    public AttendanceComparisonResponse compare(String projectId, String userId, String from, String to) {
        access.requireProjectReadAccess(projectId);
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
        String actor = access.requireCurrentUserId();
        if (!actor.equals(userId) && !actor.equals(members.ownerOf(projectId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the project owner or the worker themselves can see attendance.");
        }
        LocalDate today = calendars.today(projectId);
        LocalDate last = to == null || to.isBlank() ? today : day(to, "to");
        LocalDate first = from == null || from.isBlank() ? last.minusDays(DEFAULT_DAYS - 1) : day(from, "from");
        if (last.isBefore(first)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to must not be before from.");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        Map<LocalDate, Integer> attended = attendance.attendedMinutes(projectId, userId, first, last);
        ZoneId zone = calendars.zone(projectId);
        OffsetDateTime start = first.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = last.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        // Time without clock times by when it was recorded; with them by when it started, even past midnight (ADR-028 decision 27).
        List<ProductionRunLabor> rows = new ArrayList<>(labor
            .findAllByProjectIdAndUserIdAndCancelledYnAndRecordedAtGreaterThanEqualAndRecordedAtLessThan(projectId, userId, "N", start, end)
            .stream().filter(row -> row.getWorkedFrom() == null).toList());
        rows.addAll(labor.findAllByProjectIdAndUserIdAndCancelledYnAndWorkedFromGreaterThanEqualAndWorkedFromLessThan(
            projectId, userId, "N", start, end));
        // Time a correction added counts on the run's original end, as its rate does (ADR-028 decision 28): so the time of runs that
        // ended in the range comes in whenever its correction was approved.
        List<String> ended = runs.findAllByProjectIdAndDeletedYnAndActualEndAtGreaterThanEqualAndActualEndAtLessThan(projectId, "N", start, end)
            .stream().map(ProductionRun::getProductionRunId).toList();
        if (!ended.isEmpty()) {
            rows.addAll(labor.findAllByProductionRunIdInAndCancelledYnOrderByRecordedAtAscRunLaborIdAsc(ended, "N").stream()
                .filter(row -> userId.equals(row.getUserId()) && row.getWorkedFrom() == null).toList());
        }
        Map<String, ProductionRunLabor> unique = new LinkedHashMap<>();
        rows.forEach(row -> unique.putIfAbsent(row.getRunLaborId(), row));
        Set<String> corrected = unique.isEmpty() ? Set.of() : correctionLines.findAllByCreatedRunLaborIdIn(unique.keySet()).stream()
            .map(ProductionRunCorrectionLine::getCreatedRunLaborId).collect(Collectors.toSet());
        // Simulations and tests do not count, as in the crew comparison (LB6).
        Map<String, ProductionRun> physical = runs.findAllById(unique.values().stream().map(ProductionRunLabor::getProductionRunId)
                .collect(Collectors.toSet())).stream()
            .filter(run -> "N".equals(run.getDeletedYn()) && run.affectsPhysicalState())
            .collect(Collectors.toMap(ProductionRun::getProductionRunId, Function.identity()));
        Map<LocalDate, Integer> worked = new HashMap<>();
        for (ProductionRunLabor row : unique.values()) {
            ProductionRun run = physical.get(row.getProductionRunId());
            if (run == null) {
                continue;
            }
            OffsetDateTime at = row.getWorkedFrom() != null ? row.getWorkedFrom()
                : corrected.contains(row.getRunLaborId()) && run.getActualEndAt() != null ? run.getActualEndAt() : row.getRecordedAt();
            LocalDate day = at.atZoneSameInstant(zone).toLocalDate();
            if (!day.isBefore(first) && !day.isAfter(last)) {
                worked.merge(day, row.getWorkedMinutes(), Integer::sum);
            }
        }
        List<AttendanceComparisonResponse.Day> days = attended.entrySet().stream()
            .map(entry -> {
                int onRuns = worked.getOrDefault(entry.getKey(), 0);
                return new AttendanceComparisonResponse.Day(entry.getKey(), entry.getValue(), onRuns, entry.getValue() - onRuns);
            })
            .toList();
        return new AttendanceComparisonResponse(projectId, userId, first, last,
            days.stream().mapToInt(AttendanceComparisonResponse.Day::attendedMinutes).sum(),
            days.stream().mapToInt(AttendanceComparisonResponse.Day::laborMinutes).sum(), days);
    }

    private static LocalDate day(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is a date like 2041-09-01.");
        }
    }
}
