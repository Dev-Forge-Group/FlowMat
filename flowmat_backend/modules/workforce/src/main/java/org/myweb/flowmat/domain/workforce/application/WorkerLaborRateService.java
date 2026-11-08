package org.myweb.flowmat.domain.workforce.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerLaborRateRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerLaborRateResponse;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRateQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRates;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerLaborRate;
import org.myweb.flowmat.domain.workforce.repository.WorkerLaborRateRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workers' hourly rates in a project (docs/architecture/adr/ADR-025-worker-labor-cost.md). Only the project owner reads or
 * changes them. A rate applies from today or a later project date, so the cost of earlier dates never changes; saving the
 * same start again replaces it, and a rate removed before it started stays marked. A worker's first rate may apply from the
 * start instead (decision 11): that only gives a cost to time that had none.
 */
@Service
@RequiredArgsConstructor
public class WorkerLaborRateService implements WorkerLaborRateQuery {
    private static final int MAX_REASON = 200;
    private static final int MAX_WHOLE_DIGITS = 10;
    private static final int MAX_DECIMALS = 4;

    private final WorkerLaborRateRepository repository;
    private final ProjectAccess access;
    private final ProjectMemberQuery members;
    private final ProjectCalendarQuery calendars;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public WorkerLaborRateResponse rates(String projectId, String userId) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        return response(projectId, userId);
    }

    @Transactional
    public WorkerLaborRateResponse setRate(String projectId, String userId, WorkerLaborRateRequest request) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        BigDecimal rate = request == null ? null : request.hourlyRate();
        if (rate != null) {
            if (rate.signum() < 0 || CsvDecimalParser.hasMoreThanIntegerDigits(rate, MAX_WHOLE_DIGITS)
                || (rate.scale() > MAX_DECIMALS && rate.stripTrailingZeros().scale() > MAX_DECIMALS)) {
                throw bad("hourlyRate is 0 or more, with at most " + MAX_WHOLE_DIGITS + " whole digits and " + MAX_DECIMALS
                    + " decimals; leave it empty when unknown.");
            }
        }
        LocalDate today = calendars.today(projectId);
        String from = request == null ? null : request.effectiveFrom();
        boolean fromStart = request != null && Boolean.TRUE.equals(request.fromStart());
        LocalDate start;
        if (fromStart) {
            if (from != null && !from.isBlank()) {
                throw bad("Give either effectiveFrom or fromStart, not both.");
            }
            if (repository.findAllByProjectIdAndUserIdOrderBySetAtDescRateIdDesc(projectId, userId).stream()
                .anyMatch(row -> !row.isRemoved())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "Only a worker's first rate can apply from the start; save a new one from today or later instead.");
            }
            start = null;
        } else {
            try {
                start = from == null || from.isBlank() ? today : LocalDate.parse(from.trim());
            } catch (DateTimeParseException exception) {
                throw bad("effectiveFrom is a date like 2041-09-01.");
            }
            if (start.isBefore(today)) {
                throw bad("effectiveFrom cannot be before today (" + today + "); earlier dates keep the rate they had.");
            }
        }
        String reason = request == null ? null : request.reason();
        StorableText.require(reason, "reason");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters.");
        }
        WorkerLaborRate row = new WorkerLaborRate();
        row.setRateId(ids.generate());
        row.setProjectId(projectId);
        row.setUserId(userId);
        row.setHourlyRate(rate == null ? null : rate.setScale(MAX_DECIMALS));
        row.setEffectiveFrom(start);
        row.setReason(reason);
        row.setSetBy(access.requireCurrentUserId());
        row.setSetAt(OffsetDateTime.now());
        row.setRemovedYn("N");
        repository.saveAndFlush(row);
        return response(projectId, userId);
    }

    /** Removes the rate from a date that has not come yet, with every earlier save for that date; one that started stays. */
    @Transactional
    public WorkerLaborRateResponse removeRate(String projectId, String userId, String rateId) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        List<WorkerLaborRate> rows = repository.findAllByProjectIdAndUserIdOrderBySetAtDescRateIdDesc(projectId, userId);
        WorkerLaborRate target = rows.stream().filter(row -> row.getRateId().equals(rateId) && !row.isRemoved()).findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        LocalDate today = calendars.today(projectId);
        if (target.getEffectiveFrom() == null || !target.getEffectiveFrom().isAfter(today)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "A rate that has started stays; save a new one from a later date instead.");
        }
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        for (WorkerLaborRate row : rows) {
            if (!row.isRemoved() && target.getEffectiveFrom().equals(row.getEffectiveFrom())) {
                row.setRemovedYn("Y");
                row.setRemovedBy(actor);
                row.setRemovedAt(now);
            }
        }
        repository.saveAllAndFlush(rows);
        return response(projectId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkerLaborRates rates(String projectId, Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return new WorkerLaborRates(Collections.emptyMap());
        }
        Map<String, List<WorkerLaborRates.Rate>> byWorker = repository
            .findAllByProjectIdAndUserIdInAndRemovedYn(projectId, userIds, "N").stream()
            .collect(Collectors.groupingBy(WorkerLaborRate::getUserId,
                Collectors.mapping(row -> new WorkerLaborRates.Rate(row.getRateId(), row.getEffectiveFrom(), row.getHourlyRate(),
                    row.getSetAt()), Collectors.toList())));
        return new WorkerLaborRates(byWorker);
    }

    private WorkerLaborRateResponse response(String projectId, String userId) {
        List<WorkerLaborRate> rows = repository.findAllByProjectIdAndUserIdOrderBySetAtDescRateIdDesc(projectId, userId);
        LocalDate today = calendars.today(projectId);
        // The save that counts for each start: the last one (rows are newest first).
        Map<LocalDate, String> counting = new HashMap<>();
        Map<Boolean, String> fromStart = new HashMap<>();
        for (WorkerLaborRate row : rows) {
            if (row.isRemoved()) continue;
            if (row.getEffectiveFrom() == null) fromStart.putIfAbsent(true, row.getRateId());
            else counting.putIfAbsent(row.getEffectiveFrom(), row.getRateId());
        }
        WorkerLaborRate current = rows.stream()
            .filter(row -> !row.isRemoved() && counts(row, counting, fromStart))
            .filter(row -> row.getEffectiveFrom() == null || !row.getEffectiveFrom().isAfter(today))
            .max(Comparator.comparing(WorkerLaborRate::getEffectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
        List<WorkerLaborRateResponse.Line> lines = rows.stream()
            .map(row -> line(row, state(row, current, today, counting, fromStart)))
            .toList();
        return new WorkerLaborRateResponse(projectId, userId, today, current == null ? null : line(current, "current"), lines);
    }

    private static boolean counts(WorkerLaborRate row, Map<LocalDate, String> counting, Map<Boolean, String> fromStart) {
        String winner = row.getEffectiveFrom() == null ? fromStart.get(true) : counting.get(row.getEffectiveFrom());
        return Objects.equals(winner, row.getRateId());
    }

    private static String state(WorkerLaborRate row, WorkerLaborRate current, LocalDate today, Map<LocalDate, String> counting,
                                Map<Boolean, String> fromStart) {
        if (row.isRemoved()) return "removed";
        if (!counts(row, counting, fromStart)) return "replaced";
        if (current != null && current.getRateId().equals(row.getRateId())) return "current";
        return row.getEffectiveFrom() != null && row.getEffectiveFrom().isAfter(today) ? "upcoming" : "past";
    }

    private static WorkerLaborRateResponse.Line line(WorkerLaborRate row, String state) {
        return new WorkerLaborRateResponse.Line(row.getRateId(), row.getHourlyRate(), row.getEffectiveFrom(), row.getReason(),
            row.getSetBy(), row.getSetAt(), state, row.getRemovedBy(), row.getRemovedAt());
    }

    private void requireWorker(String projectId, String userId) {
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
