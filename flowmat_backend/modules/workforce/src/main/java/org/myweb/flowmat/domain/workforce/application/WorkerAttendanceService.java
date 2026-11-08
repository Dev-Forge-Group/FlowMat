package org.myweb.flowmat.domain.workforce.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceCancelRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceClockOutRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerAttendanceRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerAttendanceResponse;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerAttendanceQuery;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerAttendance;
import org.myweb.flowmat.domain.workforce.repository.WorkerAttendanceRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workers' attendance (docs/architecture/adr/ADR-026-attendance.md, docs/domain/worker-calendar.md AT1-AT8). Only the worker
 * themselves and the project owner record or read it. Stretches of one worker never overlap, at most one is open, and a
 * mistake is cancelled with a reason rather than deleted.
 */
@Service
@RequiredArgsConstructor
public class WorkerAttendanceService implements WorkerAttendanceQuery {
    private static final Duration LONGEST = Duration.ofHours(24);
    /** Clocks of phones and terminals run a little apart. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int MAX_NOTE = 200;
    private static final int MAX_REASON = 500;
    private static final long MAX_RANGE_DAYS = 62;
    private static final long DEFAULT_DAYS = 7;
    private static final OffsetDateTime NO_END = OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

    private final WorkerAttendanceRepository repository;
    private final ProjectAccess access;
    private final ProjectMemberQuery members;
    private final ProjectCalendarQuery calendars;
    private final IdGenerator ids;

    private record Range(LocalDate from, LocalDate to) {
    }

    @Transactional(readOnly = true)
    public WorkerAttendanceResponse attendance(String projectId, String userId, String from, String to) {
        access.requireProjectReadAccess(projectId);
        requireSelfOrOwner(projectId, userId, "see");
        return response(projectId, userId, range(projectId, from, to));
    }

    /** A stretch at work, closed or still open (AT1-AT2). The same requestId with the same contents answers as it is now. */
    @Transactional
    public WorkerAttendanceResponse record(String projectId, String userId, WorkerAttendanceRequest request) {
        access.requireProjectWriteAccess(projectId);
        requireSelfOrOwner(projectId, userId, "record");
        if (request == null || request.requestId() == null) {
            throw bad("requestId is required.");
        }
        OffsetDateTime in = time(request.clockIn(), "clockIn");
        if (in == null) {
            throw bad("clockIn is required.");
        }
        OffsetDateTime out = time(request.clockOut(), "clockOut");
        String note = text(request.note(), "note", MAX_NOTE);
        OffsetDateTime now = OffsetDateTime.now();
        if (in.isAfter(now.plus(CLOCK_SKEW))) {
            throw bad("clockIn cannot be in the future.");
        }
        if (out != null) {
            checkOut(in, out, now);
        }
        repository.lockKey("worker-attendance:" + projectId + ":" + userId);
        String actor = access.requireCurrentUserId();
        WorkerAttendance previous = repository.findByProjectIdAndUserIdAndRequestId(projectId, userId, request.requestId()).orElse(null);
        if (previous != null) {
            if (actor.equals(previous.getRecordedBy()) && previous.getClockIn().isEqual(in) && sameTime(previous.getClockOut(), out)
                && Objects.equals(previous.getNote(), note)) {
                return response(projectId, userId, range(projectId, null, null));
            }
            throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to different attendance.");
        }
        refuseOverlap(projectId, userId, null, in, out);
        WorkerAttendance row = new WorkerAttendance();
        row.setAttendanceId(ids.generate());
        row.setProjectId(projectId);
        row.setUserId(userId);
        row.setRequestId(request.requestId());
        row.setWorkDate(in.atZoneSameInstant(calendars.zone(projectId)).toLocalDate());
        row.setClockIn(in);
        row.setClockOut(out);
        row.setNote(note);
        row.setRecordedBy(actor);
        row.setRecordedAt(now);
        row.setCancelledYn("N");
        repository.saveAndFlush(row);
        return response(projectId, userId, range(projectId, null, null));
    }

    /** Clocks out of the open stretch, now when no time is given (AT3). */
    @Transactional
    public WorkerAttendanceResponse clockOut(String projectId, String userId, String attendanceId, WorkerAttendanceClockOutRequest request) {
        access.requireProjectWriteAccess(projectId);
        requireSelfOrOwner(projectId, userId, "record");
        repository.lockKey("worker-attendance:" + projectId + ":" + userId);
        WorkerAttendance row = repository.findByAttendanceIdAndProjectIdAndUserId(attendanceId, projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (row.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This attendance was cancelled.");
        }
        if (row.getClockOut() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "This attendance is already clocked out.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime out = time(request == null ? null : request.clockOut(), "clockOut");
        if (out == null) {
            out = now.truncatedTo(ChronoUnit.MICROS);
        }
        checkOut(row.getClockIn(), out, now);
        refuseOverlap(projectId, userId, row.getAttendanceId(), row.getClockIn(), out);
        row.setClockOut(out);
        repository.saveAndFlush(row);
        return response(projectId, userId, range(projectId, null, null));
    }

    /** Cancels a stretch with a reason; the row stays (AT4). */
    @Transactional
    public WorkerAttendanceResponse cancel(String projectId, String userId, String attendanceId, WorkerAttendanceCancelRequest request) {
        access.requireProjectWriteAccess(projectId);
        requireSelfOrOwner(projectId, userId, "record");
        String reason = text(request == null ? null : request.reason(), "reason", MAX_REASON);
        if (reason == null) {
            throw bad("Give a reason when cancelling attendance.");
        }
        repository.lockKey("worker-attendance:" + projectId + ":" + userId);
        WorkerAttendance row = repository.findByAttendanceIdAndProjectIdAndUserId(attendanceId, projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (row.isCancelled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "This attendance was already cancelled by " + row.getCancelledBy() + ".");
        }
        row.setCancelledYn("Y");
        row.setCancelledBy(access.requireCurrentUserId());
        row.setCancelledAt(OffsetDateTime.now());
        row.setCancelReason(reason);
        repository.saveAndFlush(row);
        return response(projectId, userId, range(projectId, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, Integer> attendedMinutes(String projectId, String userId, LocalDate from, LocalDate to) {
        Range range = new Range(from, to);
        return days(range, lines(projectId, userId, range));
    }

    private List<WorkerAttendance> lines(String projectId, String userId, Range range) {
        return repository.findAllByProjectIdAndUserIdAndWorkDateGreaterThanEqualAndWorkDateLessThanEqualOrderByClockInAscAttendanceIdAsc(
            projectId, userId, range.from(), range.to());
    }

    /** Every date of the range with the minutes of its closed stretches that count, by the date of the clock-in. */
    private static Map<LocalDate, Integer> days(Range range, List<WorkerAttendance> rows) {
        Map<LocalDate, Integer> days = new LinkedHashMap<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            days.put(day, 0);
        }
        for (WorkerAttendance row : rows) {
            if (!row.isCancelled() && row.getClockOut() != null && days.containsKey(row.getWorkDate())) {
                days.merge(row.getWorkDate(), minutes(row), Integer::sum);
            }
        }
        return days;
    }

    private WorkerAttendanceResponse response(String projectId, String userId, Range range) {
        List<WorkerAttendance> rows = lines(projectId, userId, range);
        WorkerAttendance open = repository.findFirstByProjectIdAndUserIdAndCancelledYnAndClockOutIsNullOrderByClockInDesc(projectId, userId, "N")
            .orElse(null);
        List<WorkerAttendanceResponse.Day> days = days(range, rows).entrySet().stream()
            .map(entry -> new WorkerAttendanceResponse.Day(entry.getKey(), entry.getValue()))
            .toList();
        return new WorkerAttendanceResponse(projectId, userId, range.from(), range.to(), calendars.today(projectId),
            open == null ? null : line(open), days, rows.stream().map(WorkerAttendanceService::line).toList());
    }

    private static WorkerAttendanceResponse.Line line(WorkerAttendance row) {
        return new WorkerAttendanceResponse.Line(row.getAttendanceId(), row.getWorkDate(), row.getClockIn(), row.getClockOut(),
            row.getClockOut() == null ? null : minutes(row), row.getNote(), row.getRecordedBy(), row.getRecordedAt(), row.isCancelled(),
            row.getCancelledBy(), row.getCancelledAt(), row.getCancelReason());
    }

    /** Whole minutes from clock-in to clock-out, rounded down. */
    private static int minutes(WorkerAttendance row) {
        return (int) Duration.between(row.getClockIn(), row.getClockOut()).toMinutes();
    }

    private Range range(String projectId, String from, String to) {
        LocalDate today = calendars.today(projectId);
        LocalDate last = to == null || to.isBlank() ? today : day(to, "to");
        LocalDate first = from == null || from.isBlank() ? last.minusDays(DEFAULT_DAYS - 1) : day(from, "from");
        if (last.isBefore(first)) {
            throw bad("to must not be before from.");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) {
            throw bad("A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        return new Range(first, last);
    }

    private void refuseOverlap(String projectId, String userId, String self, OffsetDateTime in, OffsetDateTime out) {
        repository.findOverlapping(projectId, userId, in, out == null ? NO_END : out).stream()
            .filter(other -> !other.getAttendanceId().equals(self))
            .findFirst()
            .ifPresent(other -> {
                throw new BusinessException(ErrorCode.CONFLICT, other.getClockOut() == null
                    ? "Already clocked in since " + other.getClockIn() + "; clock out first."
                    : "This overlaps attendance from " + other.getClockIn() + " to " + other.getClockOut() + ".");
            });
    }

    private static void checkOut(OffsetDateTime in, OffsetDateTime out, OffsetDateTime now) {
        if (!out.isAfter(in)) {
            throw bad("clockOut must be after clockIn.");
        }
        if (Duration.between(in, out).compareTo(LONGEST) > 0) {
            throw bad("A stretch at work can be at most 24 hours; record the days apart.");
        }
        if (out.isAfter(now.plus(CLOCK_SKEW))) {
            throw bad("clockOut cannot be in the future.");
        }
    }

    private static boolean sameTime(OffsetDateTime one, OffsetDateTime other) {
        return one == null ? other == null : other != null && one.isEqual(other);
    }

    private void requireSelfOrOwner(String projectId, String userId, String verb) {
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
        String actor = access.requireCurrentUserId();
        if (!actor.equals(userId) && !actor.equals(members.ownerOf(projectId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the project owner or the worker themselves can " + verb + " attendance.");
        }
    }

    private static OffsetDateTime time(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a date and time with an offset, like 2041-09-01T08:00:00+09:00.");
        }
    }

    private static LocalDate day(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a date like 2041-09-01.");
        }
    }

    private static String text(String value, String field, int max) {
        StorableText.require(value, field);
        String trimmed = value == null || value.isBlank() ? null : value.trim();
        if (trimmed != null && trimmed.length() > max) {
            throw bad(field + " must be at most " + max + " characters.");
        }
        return trimmed;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
