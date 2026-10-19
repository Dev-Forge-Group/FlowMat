package org.myweb.flowmat.domain.workforce.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.ProjectHolidayQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerDayCopyRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerDayOffRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerDayRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerShiftCopyRequest;
import org.myweb.flowmat.domain.workforce.api.dto.request.WorkerShiftsRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerAvailabilityResponse;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerCalendarResponse;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerScheduleHistoryResponse;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerScheduleResponse;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayOff;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayShift;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerRota;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerSchedule;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerShift;
import org.myweb.flowmat.domain.workforce.repository.WorkerDayOffRepository;
import org.myweb.flowmat.domain.workforce.repository.WorkerDayShiftRepository;
import org.myweb.flowmat.domain.workforce.repository.WorkerRotaRepository;
import org.myweb.flowmat.domain.workforce.repository.WorkerScheduleRepository;
import org.myweb.flowmat.domain.workforce.repository.WorkerShiftRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workers' calendars (docs/domain/worker-calendar.md WC1-WC10; user decision 2026-10-13): weekly shifts like equipment's,
 * days off, and the project's holidays. Anyone who can read the project sees every worker's calendar; the owner changes
 * anyone's and a member with write access changes their own. Weekly shifts and rotas come in versions that apply from a
 * date, so changing them leaves earlier dates as they were (docs/architecture/adr/ADR-013-rota-history.md).
 */
@Service
@RequiredArgsConstructor
public class WorkerCalendarService implements WorkerCalendarQuery {

    private static final int MAX_SHIFTS = 6;
    private static final int MAX_ROTA_WEEKS = 8;
    private static final int MAX_COPY_TARGETS = 50;
    private static final int MAX_REASON = 200;
    private static final long LONGEST_DAYS_OFF = 366;
    private static final long MAX_RANGE_DAYS = 62;
    private static final Duration LONGEST_WINDOW = Duration.ofDays(366);
    private static final long SECONDS_PER_DAY = 86_400;
    private static final long SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY;
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final List<String> DAY_NAMES = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final Comparator<WorkerSchedule> EARLIEST_FIRST =
        Comparator.comparing(WorkerSchedule::getEffectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final WorkerShiftRepository shiftRepository;
    private final WorkerDayOffRepository dayOffRepository;
    private final WorkerDayShiftRepository dayShiftRepository;
    private final WorkerRotaRepository rotaRepository;
    private final WorkerScheduleRepository scheduleRepository;
    private final WorkerScheduleHistoryService history;
    private final ProjectAccessService access;
    private final ProjectMemberQuery members;
    private final ProjectCalendarQuery calendars;
    private final ProjectHolidayQuery holidays;
    private final IdGenerator ids;

    private record Span(Instant start, Instant end) {
    }

    /** {@code week}: the rota week it runs in, 1 without a rota. */
    private record ShiftPlan(LocalTime start, LocalTime end, TreeSet<Integer> days, int week) {
    }

    /** A rota of {@code weeks} weeks whose week 1 starts on the Monday {@code startsOn}; it repeats both ways (WC45). */
    private record RotaWeeks(int weeks, LocalDate startsOn) {
        int weekOf(LocalDate day) {
            return (int) Math.floorMod(Math.floorDiv(ChronoUnit.DAYS.between(startsOn, day), 7L), (long) weeks) + 1;
        }
    }

    /** One of a date's own shifts (WC29). */
    private record DayShift(LocalTime start, LocalTime end) {
    }

    /** One version of a worker's weekly shifts and rota, from {@code from} (null: from the start) until the next (ADR-013). */
    private record Version(String scheduleId, LocalDate from, List<WorkerShift> shifts, RotaWeeks rota) {
    }

    /** A version's shifts as shown, their hours in a week and its rota. */
    private record Lines(List<WorkerCalendarResponse.Shift> shifts, BigDecimal weeklyHours, WorkerCalendarResponse.Rota rota) {
    }

    @Transactional(readOnly = true)
    public List<WorkerCalendarResponse> workers(String projectId) {
        access.requireProjectReadAccess(projectId);
        String owner = members.ownerOf(projectId);
        return members.readers(projectId).stream().map(userId -> response(projectId, userId, owner)).toList();
    }

    @Transactional(readOnly = true)
    public WorkerCalendarResponse worker(String projectId, String userId) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        return response(projectId, userId, members.ownerOf(projectId));
    }

    /**
     * A worker's weekly shifts, all at once (WC2); an empty list clears them. With {@code rota} they repeat every
     * {@code weeks} weeks from the week of {@code startsOn}, each shift in its {@code rotaWeek}; without it, any rota the
     * worker had goes (WC44-WC46). They apply from {@code effectiveFrom}, today when left out and never earlier; saving again
     * from the same date changes that version (ADR-013 decisions 3, 5, 6).
     */
    @Transactional
    public WorkerCalendarResponse setShifts(String projectId, String userId, WorkerShiftsRequest request) {
        requireEditor(projectId, userId);
        LocalDate from = effectiveFrom(projectId, request == null ? null : request.effectiveFrom());
        String reason = versionReason(request == null ? null : request.reason());
        List<WorkerShiftsRequest.Shift> requested = request == null || request.shifts() == null ? List.of() : request.shifts();
        WorkerShiftsRequest.Rota askedRota = request == null ? null : request.rota();
        int weeks = 1;
        LocalDate startsOn = null;
        if (askedRota != null) {
            if (askedRota.weeks() == null || askedRota.weeks() < 2 || askedRota.weeks() > MAX_ROTA_WEEKS) {
                throw bad("A rota runs 2 to " + MAX_ROTA_WEEKS + " weeks.");
            }
            weeks = askedRota.weeks();
            // The week, Monday to Sunday, of the date given is week 1.
            startsOn = day(askedRota.startsOn(), "startsOn").with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        if (requested.size() > MAX_SHIFTS * weeks) {
            throw bad(weeks == 1 ? "A worker can have at most " + MAX_SHIFTS + " shifts."
                : "A rota week can have at most " + MAX_SHIFTS + " shifts.");
        }
        List<ShiftPlan> plans = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            plans.add(plan(requested.get(index), requested.size() == 1 ? "" : "Shift " + (index + 1) + ": ", weeks));
        }
        for (int week = 1; week <= weeks; week++) {
            int number = week;
            if (plans.stream().filter(plan -> plan.week() == number).count() > MAX_SHIFTS) {
                throw bad("A rota week can have at most " + MAX_SHIFTS + " shifts.");
            }
        }
        requireNoOverlap(plans, weeks);
        writeShifts(projectId, userId, plans, weeks, startsOn, from, reason);
        return response(projectId, userId, members.ownerOf(projectId));
    }

    /**
     * Gives other workers this worker's saved weekly shifts and rota (WC51-WC53), for teams that take turns. Each one's
     * own weekly shifts and rota are replaced; their dates of their own and days off stay. With a rota, {@code offsetWeeks}
     * puts a worker that many weeks further on in it: while this worker works week 1 they work week 1 + offsetWeeks. Every
     * worker is checked before any is changed, and each must be someone the caller may change (WC7). The copies are each
     * worker's version from {@code effectiveFrom} (today when left out), made from this worker's version of that date (ADR-013).
     */
    @Transactional
    public List<WorkerCalendarResponse> copyShifts(String projectId, String userId, WorkerShiftCopyRequest request) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        List<WorkerShiftCopyRequest.Target> targets = request == null || request.targets() == null ? List.of() : request.targets();
        if (targets.isEmpty() || targets.size() > MAX_COPY_TARGETS) {
            throw bad("Choose 1 to " + MAX_COPY_TARGETS + " workers to copy to.");
        }
        LocalDate from = effectiveFrom(projectId, request.effectiveFrom());
        String reason = versionReason(request.reason());
        Version version = versionOn(versions(projectId, List.of(userId)).getOrDefault(userId, List.of()), from);
        List<WorkerShift> source = version == null ? List.of() : version.shifts().stream()
            .sorted(Comparator.comparing(WorkerShift::getShiftStart).thenComparing(WorkerShift::getWorkerShiftId))
            .toList();
        if (source.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, userId + " has no weekly shifts to copy.");
        }
        WorkerRota rota = rotaRepository.findByScheduleId(version.scheduleId()).orElse(null);
        int weeks = rota == null ? 1 : rota.getCycleWeeks();
        List<ShiftPlan> plans = source.stream()
            .map(shift -> new ShiftPlan(shift.getShiftStart(), shift.getShiftEnd(), workDays(shift.getWorkDays()),
                shift.getRotaWeek()))
            .toList();
        Map<String, Integer> offsets = new java.util.LinkedHashMap<>();
        for (WorkerShiftCopyRequest.Target target : targets) {
            String id = target == null || target.userId() == null ? "" : target.userId().trim();
            if (id.isEmpty() || id.equals(userId)) {
                throw bad("Choose other workers to copy to.");
            }
            if (offsets.containsKey(id)) {
                throw bad(id + " is chosen twice.");
            }
            if (!members.canRead(projectId, id)) {
                throw bad(id + " is not a worker of this project.");
            }
            int offset = target.offsetWeeks() == null ? 0 : target.offsetWeeks();
            if (weeks == 1 && offset != 0) {
                throw bad("offsetWeeks needs a rota; " + userId + " works the same every week.");
            }
            if (offset < 0 || offset >= weeks) {
                throw bad("offsetWeeks is 0 to " + (weeks - 1) + ".");
            }
            requireEditor(projectId, id);
            offsets.put(id, offset);
        }
        String owner = members.ownerOf(projectId);
        List<WorkerCalendarResponse> copied = new ArrayList<>();
        for (Map.Entry<String, Integer> target : offsets.entrySet()) {
            // Starting that many weeks earlier puts them that many weeks further on in the rota (WC45).
            LocalDate startsOn = rota == null ? null : rota.getStartsOn().minusWeeks(target.getValue());
            writeShifts(projectId, target.getKey(), plans, weeks, startsOn, from, reason);
            copied.add(response(projectId, target.getKey(), owner));
        }
        return copied;
    }

    /**
     * Makes these the worker's weekly shifts and rota from {@code from} (WC2, WC44): saving again from the same date changes
     * that version, one version a day (ADR-013 decision 5), and its reason with it (ADR-017 decision 3). A rota without shifts
     * would repeat nothing.
     */
    private void writeShifts(String projectId, String userId, List<ShiftPlan> plans, int weeks, LocalDate startsOn, LocalDate from,
                             String reason) {
        shiftRepository.lockKey("worker-shift:" + projectId + ":" + userId);
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        WorkerSchedule schedule = scheduleRepository.findAllByProjectIdAndUserId(projectId, userId).stream()
            .filter(one -> from.equals(one.getEffectiveFrom()))
            .findFirst()
            .orElse(null);
        if (schedule == null) {
            schedule = new WorkerSchedule();
            schedule.setScheduleId(ids.generate());
            schedule.setProjectId(projectId);
            schedule.setUserId(userId);
            schedule.setEffectiveFrom(from);
        } else {
            shiftRepository.deleteAll(shiftRepository.findAllByScheduleIdOrderByShiftStartAscWorkerShiftIdAsc(schedule.getScheduleId()));
            shiftRepository.flush();
            rotaRepository.findByScheduleId(schedule.getScheduleId()).ifPresent(rotaRepository::delete);
            rotaRepository.flush();
        }
        schedule.setReason(reason);
        schedule.setUpdatedBy(actor);
        schedule.setUpdatedAt(now);
        scheduleRepository.saveAndFlush(schedule);
        List<WorkerShift> saved = new ArrayList<>();
        for (ShiftPlan plan : plans) {
            WorkerShift shift = new WorkerShift();
            shift.setWorkerShiftId(ids.generate());
            shift.setProjectId(projectId);
            shift.setUserId(userId);
            shift.setScheduleId(schedule.getScheduleId());
            shift.setShiftStart(plan.start());
            shift.setShiftEnd(plan.end());
            shift.setWorkDays(plan.days().stream().map(String::valueOf).collect(Collectors.joining(",")));
            shift.setRotaWeek(plan.week());
            shift.setUpdatedBy(actor);
            shift.setUpdatedAt(now);
            shiftRepository.save(shift);
            saved.add(shift);
        }
        shiftRepository.flush();
        WorkerRota rota = null;
        if (weeks > 1 && !plans.isEmpty()) {
            rota = new WorkerRota();
            rota.setWorkerRotaId(ids.generate());
            rota.setProjectId(projectId);
            rota.setUserId(userId);
            rota.setScheduleId(schedule.getScheduleId());
            rota.setCycleWeeks(weeks);
            rota.setStartsOn(startsOn);
            rota.setUpdatedBy(actor);
            rota.setUpdatedAt(now);
            rotaRepository.saveAndFlush(rota);
        }
        // Each save stays in the history, also when it changes a version saved before (ADR-024).
        history.record(schedule, "saved", saved, rota, actor, now);
    }

    /** One version of the worker's weekly shifts and rota (ADR-013 decision 7). */
    @Transactional(readOnly = true)
    public WorkerScheduleResponse schedule(String projectId, String userId, String scheduleId) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        WorkerSchedule schedule = scheduleRepository.findByScheduleIdAndProjectIdAndUserId(scheduleId, projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        LocalDate today = calendars.today(projectId);
        WorkerSchedule current = currentOf(scheduleRepository.findAllByProjectIdAndUserId(projectId, userId).stream()
            .sorted(EARLIEST_FIRST).toList(), today);
        Lines lines = lines(schedule.getScheduleId(), today);
        return new WorkerScheduleResponse(schedule.getScheduleId(), schedule.getEffectiveFrom(),
            current != null && current.getScheduleId().equals(schedule.getScheduleId()), !lines.shifts().isEmpty(),
            lines.weeklyHours(), lines.shifts(), lines.rota(), schedule.getReason(), schedule.getUpdatedBy(), schedule.getUpdatedAt());
    }

    /** Every save and removal of the worker's versions, newest first, at most 100 (docs/architecture/adr/ADR-024-version-save-history.md). */
    @Transactional(readOnly = true)
    public List<WorkerScheduleHistoryResponse> scheduleHistory(String projectId, String userId) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        return history.list(projectId, userId);
    }

    /** Deletes a version that has not started yet (ADR-013 decision 4); one that has started stays as the worker's history. */
    @Transactional
    public WorkerCalendarResponse removeSchedule(String projectId, String userId, String scheduleId) {
        requireEditor(projectId, userId);
        shiftRepository.lockKey("worker-shift:" + projectId + ":" + userId);
        WorkerSchedule schedule = scheduleRepository.findByScheduleIdAndProjectIdAndUserId(scheduleId, projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (schedule.getEffectiveFrom() == null || !schedule.getEffectiveFrom().isAfter(calendars.today(projectId))) {
            throw new BusinessException(ErrorCode.CONFLICT, "This schedule has started; save a new one from a later date instead.");
        }
        List<WorkerShift> shifts = shiftRepository.findAllByScheduleIdOrderByShiftStartAscWorkerShiftIdAsc(scheduleId);
        WorkerRota rota = rotaRepository.findByScheduleId(scheduleId).orElse(null);
        // The removed version stays in the history with what it held (ADR-024).
        history.record(schedule, "removed", shifts, rota, access.requireCurrentUserId(), OffsetDateTime.now());
        shiftRepository.deleteAll(shifts);
        shiftRepository.flush();
        if (rota != null) {
            rotaRepository.delete(rota);
        }
        rotaRepository.flush();
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();
        return response(projectId, userId, members.ownerOf(projectId));
    }

    @Transactional
    public WorkerCalendarResponse addDayOff(String projectId, String userId, WorkerDayOffRequest request) {
        requireEditor(projectId, userId);
        if (request == null) {
            throw bad("firstDay and lastDay are required.");
        }
        LocalDate first = day(request.firstDay(), "firstDay");
        LocalDate last = request.lastDay() == null || request.lastDay().isBlank() ? first : day(request.lastDay(), "lastDay");
        if (last.isBefore(first)) {
            throw bad("lastDay must not be before firstDay.");
        }
        if (ChronoUnit.DAYS.between(first, last) > LONGEST_DAYS_OFF) {
            throw bad("Days off can span at most " + LONGEST_DAYS_OFF + " days at a time.");
        }
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters.");
        }
        WorkerDayOff dayOff = new WorkerDayOff();
        dayOff.setDayOffId(ids.generate());
        dayOff.setProjectId(projectId);
        dayOff.setUserId(userId);
        dayOff.setFirstDay(first);
        dayOff.setLastDay(last);
        dayOff.setReason(reason);
        dayOff.setCreatedBy(access.requireCurrentUserId());
        dayOff.setCreatedAt(OffsetDateTime.now());
        dayOffRepository.saveAndFlush(dayOff);
        return response(projectId, userId, members.ownerOf(projectId));
    }

    @Transactional
    public WorkerCalendarResponse removeDayOff(String projectId, String userId, String dayOffId) {
        requireEditor(projectId, userId);
        WorkerDayOff dayOff = dayOffRepository.findByDayOffIdAndProjectIdAndUserId(dayOffId, projectId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        dayOffRepository.delete(dayOff);
        dayOffRepository.flush();
        return response(projectId, userId, members.ownerOf(projectId));
    }

    /**
     * A date's own shifts in place of the worker's weekly ones (WC29-WC31); none means they do not work that day. With
     * {@code throughDate} every date up to it (at most 62), on {@code weekDays} only when given, gets the same. The worker
     * needs weekly shifts first, as equipment needs a calendar (D2).
     */
    @Transactional
    public WorkerCalendarResponse setDay(String projectId, String userId, String date, WorkerDayRequest request) {
        requireEditor(projectId, userId);
        LocalDate first = day(date, "date");
        LocalDate through = request == null || request.throughDate() == null || request.throughDate().isBlank()
            ? first : day(request.throughDate(), "throughDate");
        if (through.isBefore(first)) {
            throw bad("throughDate must not be before the date.");
        }
        if (ChronoUnit.DAYS.between(first, through) >= MAX_RANGE_DAYS) {
            throw bad("A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        List<Integer> askedDays = request == null || request.weekDays() == null ? List.of() : request.weekDays();
        if (askedDays.stream().anyMatch(one -> one == null || one < 1 || one > 7)) {
            throw bad("weekDays are 1 (Monday) to 7 (Sunday).");
        }
        Set<Integer> weekDays = Set.copyOf(askedDays);
        List<LocalDate> dates = first.datesUntil(through.plusDays(1))
            .filter(one -> weekDays.isEmpty() || weekDays.contains(one.getDayOfWeek().getValue()))
            .toList();
        if (dates.isEmpty()) {
            throw bad("None of the dates fall on the chosen days of the week.");
        }
        shiftRepository.lockKey("worker-shift:" + projectId + ":" + userId);
        if (shiftRepository.findAllByProjectIdAndUserIdOrderByShiftStartAscWorkerShiftIdAsc(projectId, userId).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Set " + userId + "'s weekly shifts first; a date's shifts take the place of them.");
        }
        List<WorkerDayRequest.Shift> requested = request == null || request.shifts() == null ? List.of() : request.shifts();
        if (requested.size() > MAX_SHIFTS) {
            throw bad("A day can have at most " + MAX_SHIFTS + " shifts.");
        }
        List<DayShift> shifts = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            String prefix = requested.size() == 1 ? "" : "Shift " + (index + 1) + ": ";
            WorkerDayRequest.Shift shift = requested.get(index);
            if (shift == null) {
                throw bad(prefix + "shiftStart and shiftEnd are required.");
            }
            try {
                shifts.add(new DayShift(time(shift.shiftStart(), "shiftStart").withSecond(0).withNano(0),
                    time(shift.shiftEnd(), "shiftEnd").withSecond(0).withNano(0)));
            } catch (BusinessException exception) {
                throw prefix.isEmpty() ? exception : bad(prefix + exception.getMessage());
            }
        }
        requireNoDayOverlap(shifts);
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters.");
        }
        String text = shifts.stream()
            .map(shift -> HOUR_MINUTE.format(shift.start()) + "-" + HOUR_MINUTE.format(shift.end()))
            .collect(Collectors.joining(","));
        Map<LocalDate, WorkerDayShift> existing = dayShiftRepository
            .findAllByProjectIdAndUserIdInAndWorkDateBetween(projectId, List.of(userId), first, through).stream()
            .collect(Collectors.toMap(WorkerDayShift::getWorkDate, row -> row));
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        List<WorkerDayShift> saved = new ArrayList<>();
        for (LocalDate one : dates) {
            WorkerDayShift row = existing.get(one);
            if (row == null) {
                row = new WorkerDayShift();
                row.setDayShiftId(ids.generate());
                row.setProjectId(projectId);
                row.setUserId(userId);
                row.setWorkDate(one);
            }
            row.setShifts(text);
            row.setReason(reason);
            row.setUpdatedBy(actor);
            row.setUpdatedAt(now);
            saved.add(row);
        }
        dayShiftRepository.saveAll(saved);
        dayShiftRepository.flush();
        return response(projectId, userId, members.ownerOf(projectId));
    }

    /** The date goes back to the weekly shifts; with {@code through}, every date of its own up to that one (WC32). */
    @Transactional
    public WorkerCalendarResponse clearDay(String projectId, String userId, String date, String through) {
        requireEditor(projectId, userId);
        LocalDate first = day(date, "date");
        LocalDate last = through == null || through.isBlank() ? first : day(through, "through");
        if (last.isBefore(first)) {
            throw bad("through must not be before the date.");
        }
        List<WorkerDayShift> found = dayShiftRepository.findAllByProjectIdAndUserIdInAndWorkDateBetween(projectId, List.of(userId),
            first, last);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        dayShiftRepository.deleteAll(found);
        dayShiftRepository.flush();
        return response(projectId, userId, members.ownerOf(projectId));
    }

    /**
     * Copies a worker's own dates (from {@code fromDate} through {@code throughDate}, either side open) to another worker of
     * the project, replacing what those dates had there; their other dates stay (WC41, like equipment D7). The one copied
     * to needs weekly shifts, as for a date of their own, and must be someone the caller may change (WC7).
     */
    @Transactional
    public WorkerCalendarResponse copyDays(String projectId, String userId, WorkerDayCopyRequest request) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        String target = request == null || request.toUserId() == null ? "" : request.toUserId().trim();
        if (target.isEmpty() || target.equals(userId)) {
            throw bad("Choose another person to copy to.");
        }
        if (!members.canRead(projectId, target)) {
            throw bad("toUserId is not a worker of this project.");
        }
        requireEditor(projectId, target);
        LocalDate from = request.fromDate() == null || request.fromDate().isBlank() ? null : day(request.fromDate(), "fromDate");
        LocalDate through = request.throughDate() == null || request.throughDate().isBlank()
            ? null : day(request.throughDate(), "throughDate");
        if (from != null && through != null && through.isBefore(from)) {
            throw bad("throughDate must not be before fromDate.");
        }
        shiftRepository.lockKey("worker-shift:" + projectId + ":" + target);
        if (shiftRepository.findAllByProjectIdAndUserIdOrderByShiftStartAscWorkerShiftIdAsc(projectId, target).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Set " + target + "'s weekly shifts first; a date's shifts take the place of them.");
        }
        List<WorkerDayShift> copied = dayShiftRepository.findAllByProjectIdAndUserIdOrderByWorkDateAsc(projectId, userId).stream()
            .filter(row -> from == null || !row.getWorkDate().isBefore(from))
            .filter(row -> through == null || !row.getWorkDate().isAfter(through))
            .toList();
        if (copied.isEmpty()) {
            throw bad("There are no day changes to copy.");
        }
        Map<LocalDate, WorkerDayShift> existing = dayShiftRepository
            .findAllByProjectIdAndUserIdInAndWorkDateBetween(projectId, List.of(target), copied.get(0).getWorkDate(),
                copied.get(copied.size() - 1).getWorkDate()).stream()
            .collect(Collectors.toMap(WorkerDayShift::getWorkDate, row -> row));
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        List<WorkerDayShift> saved = new ArrayList<>();
        for (WorkerDayShift source : copied) {
            WorkerDayShift row = existing.get(source.getWorkDate());
            if (row == null) {
                row = new WorkerDayShift();
                row.setDayShiftId(ids.generate());
                row.setProjectId(projectId);
                row.setUserId(target);
                row.setWorkDate(source.getWorkDate());
            }
            row.setShifts(source.getShifts());
            row.setReason(source.getReason());
            row.setUpdatedBy(actor);
            row.setUpdatedAt(now);
            saved.add(row);
        }
        dayShiftRepository.saveAll(saved);
        dayShiftRepository.flush();
        return response(projectId, target, members.ownerOf(projectId));
    }

    @Transactional(readOnly = true)
    public WorkerAvailabilityResponse availability(String projectId, String userId, String from, String to) {
        access.requireProjectReadAccess(projectId);
        requireWorker(projectId, userId);
        OffsetDateTime start = instant(from, "from");
        OffsetDateTime end = instant(to, "to");
        if (!end.isAfter(start)) {
            throw bad("to must be after from.");
        }
        if (Duration.between(start, end).compareTo(LONGEST_WINDOW) > 0) {
            throw bad("The window can be at most 366 days.");
        }
        ZoneId zone = calendars.zone(projectId);
        LocalDate firstDay = start.atZoneSameInstant(zone).toLocalDate();
        LocalDate lastDay = end.minusNanos(1).atZoneSameInstant(zone).toLocalDate();
        List<Version> versions = versions(projectId, List.of(userId)).getOrDefault(userId, List.of());
        List<WorkerDayOff> offs = dayOffRepository.findAllByProjectIdAndUserIdInAndLastDayGreaterThanEqualAndFirstDayLessThanEqual(
            projectId, List.of(userId), firstDay.minusDays(1), lastDay);
        Set<LocalDate> holidayDays = holidays.holidays(projectId, firstDay.minusDays(1), lastDay);
        Map<LocalDate, String> ownDays = ownDays(dayShiftRepository.findAllByProjectIdAndUserIdInAndWorkDateBetween(projectId,
            List.of(userId), firstDay.minusDays(1), lastDay));
        WorkerHours hours = hours(spans(versions, offs, holidayDays, ownDays, zone, start.toInstant(), end.toInstant()));
        // A date with its own shifts is neither off nor a holiday for the worker (WC30).
        TreeSet<LocalDate> offDays = new TreeSet<>();
        for (WorkerDayOff off : offs) {
            for (LocalDate day = off.getFirstDay(); !day.isAfter(off.getLastDay()); day = day.plusDays(1)) {
                if (!day.isBefore(firstDay) && !day.isAfter(lastDay) && !ownDays.containsKey(day)) {
                    offDays.add(day);
                }
            }
        }
        List<LocalDate> holidaysInWindow = holidayDays.stream()
            .filter(day -> !day.isBefore(firstDay) && !day.isAfter(lastDay) && !ownDays.containsKey(day)).sorted().toList();
        return new WorkerAvailabilityResponse(userId, start, end, hours.calendarSet(), hours.hours(), List.copyOf(offDays),
            holidaysInWindow);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, WorkerHours> workingHours(String projectId, Collection<String> userIds, OffsetDateTime from,
                                                 OffsetDateTime to) {
        Map<String, WorkerHours> result = new HashMap<>();
        workingTime(projectId, userIds, from, to).forEach((userId, time) -> result.put(userId, hours(time)));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, WorkerTime> workingTime(String projectId, Collection<String> userIds, OffsetDateTime from,
                                               OffsetDateTime to) {
        if (userIds.isEmpty() || !to.isAfter(from)) {
            return userIds.stream().distinct().collect(Collectors.toMap(id -> id, id -> new WorkerTime(false, List.of())));
        }
        ZoneId zone = calendars.zone(projectId);
        LocalDate firstDay = from.atZoneSameInstant(zone).toLocalDate().minusDays(1);
        LocalDate lastDay = to.atZoneSameInstant(zone).toLocalDate();
        Map<String, List<Version>> versions = versions(projectId, userIds);
        Map<String, List<WorkerDayOff>> offs = dayOffRepository
            .findAllByProjectIdAndUserIdInAndLastDayGreaterThanEqualAndFirstDayLessThanEqual(projectId, userIds, firstDay, lastDay)
            .stream().collect(Collectors.groupingBy(WorkerDayOff::getUserId));
        Set<LocalDate> holidayDays = holidays.holidays(projectId, firstDay, lastDay);
        Map<String, List<WorkerDayShift>> days = dayShiftRepository
            .findAllByProjectIdAndUserIdInAndWorkDateBetween(projectId, userIds, firstDay, lastDay).stream()
            .collect(Collectors.groupingBy(WorkerDayShift::getUserId));
        Map<String, WorkerTime> result = new HashMap<>();
        for (String userId : new HashSet<>(userIds)) {
            result.put(userId, spans(versions.getOrDefault(userId, List.of()), offs.getOrDefault(userId, List.of()), holidayDays,
                ownDays(days.getOrDefault(userId, List.of())), zone, from.toInstant(), to.toInstant()));
        }
        return result;
    }

    /**
     * Each shift on each of its work days, from the day before the window (a night shift runs into it), clipped to the
     * window and merged. Each date takes the shifts of the version that applies on it (ADR-013), and with a rota those of
     * its rota week (WC45). No shift starts on a day off or a project holiday; a night shift that started the day before still
     * runs into it, as for equipment. A date with its own shifts uses those instead, day off or holiday (WC30). The calendar
     * counts as set when a date in the window has weekly shifts or its own.
     */
    private static WorkerTime spans(List<Version> versions, List<WorkerDayOff> offs, Set<LocalDate> holidayDays,
                                    Map<LocalDate, String> ownDays, ZoneId zone, Instant start, Instant end) {
        if (versions.stream().allMatch(version -> version.shifts().isEmpty())) {
            return new WorkerTime(false, List.of());
        }
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate();
        boolean set = false;
        List<Span> spans = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            Version version = versionOn(versions, day);
            boolean weekly = version != null && !version.shifts().isEmpty();
            String own = ownDays.get(day);
            set |= day.isAfter(first) && (weekly || own != null);
            if (own != null) {
                for (DayShift shift : dayShifts(own)) {
                    LocalDateTime shiftStart = day.atTime(shift.start());
                    Span span = clip(shiftStart.atZone(zone).toInstant(),
                        shiftStart.plus(shiftLength(shift.start(), shift.end())).atZone(zone).toInstant(), start, end);
                    if (span != null) {
                        spans.add(span);
                    }
                }
                continue;
            }
            LocalDate current = day;
            boolean off = holidayDays.contains(day)
                || offs.stream().anyMatch(dayOff -> !current.isBefore(dayOff.getFirstDay()) && !current.isAfter(dayOff.getLastDay()));
            if (off || !weekly) {
                continue;
            }
            int week = version.rota() == null ? 1 : version.rota().weekOf(day);
            for (WorkerShift shift : version.shifts()) {
                if (shift.getRotaWeek() != week || !workDays(shift.getWorkDays()).contains(day.getDayOfWeek().getValue())) {
                    continue;
                }
                LocalDateTime shiftStart = day.atTime(shift.getShiftStart());
                Span span = clip(shiftStart.atZone(zone).toInstant(),
                    shiftStart.plus(shiftLength(shift.getShiftStart(), shift.getShiftEnd())).atZone(zone).toInstant(), start, end);
                if (span != null) {
                    spans.add(span);
                }
            }
        }
        return new WorkerTime(set, merge(spans).stream().map(span -> new WorkerTime.Span(span.start(), span.end())).toList());
    }

    /** Each worker's versions of weekly shifts and rota, earliest first (ADR-013). */
    private Map<String, List<Version>> versions(String projectId, Collection<String> userIds) {
        Map<String, List<WorkerShift>> shifts = shiftRepository.findAllByProjectIdAndUserIdIn(projectId, userIds).stream()
            .collect(Collectors.groupingBy(WorkerShift::getScheduleId));
        Map<String, WorkerRota> rotas = rotaRepository.findAllByProjectIdAndUserIdIn(projectId, userIds).stream()
            .collect(Collectors.toMap(WorkerRota::getScheduleId, rota -> rota));
        Map<String, List<Version>> versions = new HashMap<>();
        scheduleRepository.findAllByProjectIdAndUserIdIn(projectId, userIds).stream()
            .sorted(EARLIEST_FIRST)
            .forEach(schedule -> versions.computeIfAbsent(schedule.getUserId(), id -> new ArrayList<>())
                .add(new Version(schedule.getScheduleId(), schedule.getEffectiveFrom(),
                    shifts.getOrDefault(schedule.getScheduleId(), List.of()), rotaWeeks(rotas.get(schedule.getScheduleId())))));
        return versions;
    }

    /** The version that applies on the day: the last one that has started by then; none before the first. */
    private static Version versionOn(List<Version> versions, LocalDate day) {
        Version found = null;
        for (Version version : versions) {
            if (version.from() == null || !version.from().isAfter(day)) {
                found = version;
            }
        }
        return found;
    }

    /** The version of today among the worker's, earliest first; none when all start later. */
    private static WorkerSchedule currentOf(List<WorkerSchedule> schedules, LocalDate today) {
        WorkerSchedule found = null;
        for (WorkerSchedule schedule : schedules) {
            if (schedule.getEffectiveFrom() == null || !schedule.getEffectiveFrom().isAfter(today)) {
                found = schedule;
            }
        }
        return found;
    }

    /** The date a saved version starts (ADR-013 decisions 3 and 6): today when left out, never earlier. */
    private LocalDate effectiveFrom(String projectId, String value) {
        LocalDate today = calendars.today(projectId);
        if (value == null || value.isBlank()) {
            return today;
        }
        LocalDate from = day(value, "effectiveFrom");
        if (from.isBefore(today)) {
            throw bad("effectiveFrom must be today (" + today + ") or later; change a past date with that date's own shifts.");
        }
        return from;
    }

    private static RotaWeeks rotaWeeks(WorkerRota rota) {
        return rota == null ? null : new RotaWeeks(rota.getCycleWeeks(), rota.getStartsOn());
    }

    private static WorkerHours hours(WorkerTime time) {
        return new WorkerHours(time.calendarSet(), hours(time.seconds()));
    }

    private static Map<LocalDate, String> ownDays(List<WorkerDayShift> rows) {
        Map<LocalDate, String> days = new HashMap<>();
        rows.forEach(row -> days.put(row.getWorkDate(), row.getShifts()));
        return days;
    }

    /** "06:00-14:00,14:00-18:00" as shifts; none for a day without work. */
    private static List<DayShift> dayShifts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<DayShift> shifts = new ArrayList<>();
        for (String pair : text.split(",")) {
            String[] times = pair.split("-");
            shifts.add(new DayShift(LocalTime.parse(times[0]), LocalTime.parse(times[1])));
        }
        return shifts;
    }

    private static WorkerCalendarResponse.Day dayLine(WorkerDayShift row) {
        List<DayShift> own = dayShifts(row.getShifts());
        List<WorkerCalendarResponse.DayShift> shifts = own.stream()
            .map(shift -> new WorkerCalendarResponse.DayShift(HOUR_MINUTE.format(shift.start()), HOUR_MINUTE.format(shift.end()),
                hours(shiftLength(shift.start(), shift.end()).getSeconds())))
            .toList();
        long seconds = own.stream().mapToLong(shift -> shiftLength(shift.start(), shift.end()).getSeconds()).sum();
        return new WorkerCalendarResponse.Day(row.getWorkDate(), shifts, shifts.isEmpty(), hours(seconds), row.getReason(),
            row.getUpdatedBy());
    }

    /** Refuses two of a date's shifts that share any second (a night shift runs into the next morning), as for equipment. */
    private static void requireNoDayOverlap(List<DayShift> shifts) {
        for (int first = 0; first < shifts.size(); first++) {
            for (int second = first + 1; second < shifts.size(); second++) {
                long firstStart = shifts.get(first).start().toSecondOfDay();
                long secondStart = shifts.get(second).start().toSecondOfDay();
                long firstEnd = firstStart + shiftLength(shifts.get(first).start(), shifts.get(first).end()).getSeconds();
                long secondEnd = secondStart + shiftLength(shifts.get(second).start(), shifts.get(second).end()).getSeconds();
                if (Math.max(firstStart, secondStart) < Math.min(firstEnd, secondEnd)) {
                    throw bad("Shifts " + (first + 1) + " and " + (second + 1) + " overlap.");
                }
            }
        }
    }

    private WorkerCalendarResponse response(String projectId, String userId, String owner) {
        LocalDate today = calendars.today(projectId);
        List<WorkerSchedule> schedules = scheduleRepository.findAllByProjectIdAndUserId(projectId, userId).stream()
            .sorted(EARLIEST_FIRST).toList();
        WorkerSchedule current = currentOf(schedules, today);
        // The shifts and rota shown are the version of today (ADR-013 decision 7).
        Lines lines = lines(current == null ? null : current.getScheduleId(), today);
        LocalDate recent = today.minusDays(31);
        List<WorkerCalendarResponse.DayOff> daysOff = dayOffRepository
            .findAllByProjectIdAndUserIdAndLastDayGreaterThanEqualOrderByFirstDayAscDayOffIdAsc(projectId, userId, recent).stream()
            .map(off -> new WorkerCalendarResponse.DayOff(off.getDayOffId(), off.getFirstDay(), off.getLastDay(), off.getReason(),
                off.getCreatedBy()))
            .toList();
        List<WorkerCalendarResponse.Day> days = dayShiftRepository
            .findAllByProjectIdAndUserIdAndWorkDateGreaterThanEqualOrderByWorkDateAsc(projectId, userId, recent).stream()
            .map(WorkerCalendarService::dayLine)
            .toList();
        List<WorkerCalendarResponse.Schedule> versions = schedules.stream()
            .map(schedule -> new WorkerCalendarResponse.Schedule(schedule.getScheduleId(), schedule.getEffectiveFrom(),
                schedule == current, schedule.getReason(), schedule.getUpdatedBy(), schedule.getUpdatedAt()))
            .toList();
        return new WorkerCalendarResponse(projectId, userId, userId.equals(owner), !lines.shifts().isEmpty(), lines.weeklyHours(),
            lines.shifts(), daysOff, days, lines.rota(), versions);
    }

    /** A version's shifts in rota week order, their hours in a week (with a rota, the average of its weeks, WC47) and rota. */
    private Lines lines(String scheduleId, LocalDate today) {
        List<WorkerShift> shifts = scheduleId == null ? List.of()
            : shiftRepository.findAllByScheduleIdOrderByShiftStartAscWorkerShiftIdAsc(scheduleId).stream()
                .sorted(Comparator.comparingInt(WorkerShift::getRotaWeek)).toList();
        WorkerRota rota = scheduleId == null ? null : rotaRepository.findByScheduleId(scheduleId).orElse(null);
        long weekly = 0;
        List<WorkerCalendarResponse.Shift> shiftLines = new ArrayList<>();
        for (WorkerShift shift : shifts) {
            long length = shiftLength(shift.getShiftStart(), shift.getShiftEnd()).getSeconds();
            TreeSet<Integer> days = workDays(shift.getWorkDays());
            weekly += length * days.size();
            shiftLines.add(new WorkerCalendarResponse.Shift(shift.getWorkerShiftId(), shift.getShiftStart().format(HOUR_MINUTE),
                shift.getShiftEnd().format(HOUR_MINUTE), List.copyOf(days), hours(length), shift.getRotaWeek()));
        }
        int weeks = rota == null ? 1 : rota.getCycleWeeks();
        BigDecimal weeklyHours = BigDecimal.valueOf(weekly)
            .divide(SECONDS_PER_HOUR.multiply(BigDecimal.valueOf(weeks)), 2, RoundingMode.HALF_UP);
        WorkerCalendarResponse.Rota rotaLine = rota == null ? null
            : new WorkerCalendarResponse.Rota(rota.getCycleWeeks(), rota.getStartsOn(), rotaWeeks(rota).weekOf(today));
        return new Lines(List.copyOf(shiftLines), weeklyHours, rotaLine);
    }

    /** The owner changes anyone's calendar; a member with write access changes their own (WC7). */
    private void requireEditor(String projectId, String userId) {
        access.requireProjectWriteAccess(projectId);
        requireWorker(projectId, userId);
        String actor = access.requireCurrentUserId();
        if (!actor.equals(userId) && !actor.equals(members.ownerOf(projectId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                "Only the project owner or the worker themselves can change this calendar.");
        }
    }

    private void requireWorker(String projectId, String userId) {
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
    }

    private static ShiftPlan plan(WorkerShiftsRequest.Shift shift, String prefix, int weeks) {
        if (shift == null) {
            throw bad(prefix + "shiftStart, shiftEnd and workDays are required.");
        }
        try {
            LocalTime start = time(shift.shiftStart(), "shiftStart");
            LocalTime end = time(shift.shiftEnd(), "shiftEnd");
            if (shift.workDays() == null || shift.workDays().isEmpty()) {
                throw bad("Choose at least one work day.");
            }
            TreeSet<Integer> days = new TreeSet<>();
            for (Integer day : shift.workDays()) {
                if (day == null || day < 1 || day > 7) {
                    throw bad("workDays are ISO days of the week, 1 (Monday) to 7 (Sunday).");
                }
                days.add(day);
            }
            int week = shift.rotaWeek() == null ? 1 : shift.rotaWeek();
            if (week < 1 || week > weeks) {
                throw bad(weeks == 1 ? "rotaWeek needs a rota; send rota with weeks and startsOn."
                    : "rotaWeek is 1 to " + weeks + ".");
            }
            return new ShiftPlan(start, end, days, week);
        } catch (BusinessException exception) {
            throw prefix.isEmpty() ? exception : bad(prefix + exception.getMessage());
        }
    }

    /**
     * Two shifts that share any second of the week, or of the whole rota, are refused (WC2, WC45); shifts that only touch are
     * fine. The last week's Sunday night runs into the first week's Monday.
     */
    private static void requireNoOverlap(List<ShiftPlan> plans, int weeks) {
        record Piece(int shift, long start, long end) {
        }
        long cycle = weeks * SECONDS_PER_WEEK;
        List<Piece> pieces = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            ShiftPlan plan = plans.get(index);
            long length = shiftLength(plan.start(), plan.end()).getSeconds();
            for (int day : plan.days()) {
                long start = (plan.week() - 1) * SECONDS_PER_WEEK + (day - 1) * SECONDS_PER_DAY + plan.start().toSecondOfDay();
                long end = start + length;
                if (end <= cycle) {
                    pieces.add(new Piece(index, start, end));
                } else {
                    pieces.add(new Piece(index, start, cycle));
                    pieces.add(new Piece(index, 0, end - cycle));
                }
            }
        }
        for (Piece first : pieces) {
            for (Piece second : pieces) {
                long overlapStart = Math.max(first.start(), second.start());
                if (first.shift() < second.shift() && overlapStart < Math.min(first.end(), second.end())) {
                    String day = DAY_NAMES.get((int) (overlapStart % SECONDS_PER_WEEK / SECONDS_PER_DAY));
                    throw bad("Shifts " + (first.shift() + 1) + " and " + (second.shift() + 1) + " overlap "
                        + (weeks == 1 ? "on " + day : "in week " + (overlapStart / SECONDS_PER_WEEK + 1) + " on " + day) + ".");
                }
            }
        }
    }

    private static TreeSet<Integer> workDays(String text) {
        TreeSet<Integer> days = new TreeSet<>();
        for (String day : text.split(",")) {
            days.add(Integer.parseInt(day.trim()));
        }
        return days;
    }

    /** A shift that ends at or before its start runs into the next day; equal times are a whole day. */
    private static Duration shiftLength(LocalTime start, LocalTime end) {
        Duration length = Duration.between(start, end);
        return length.isNegative() || length.isZero() ? length.plusHours(24) : length;
    }

    private static Span clip(Instant start, Instant end, Instant from, Instant to) {
        Instant clippedStart = start.isBefore(from) ? from : start;
        Instant clippedEnd = end.isAfter(to) ? to : end;
        return clippedEnd.isAfter(clippedStart) ? new Span(clippedStart, clippedEnd) : null;
    }

    private static List<Span> merge(List<Span> spans) {
        List<Span> sorted = spans.stream().sorted(Comparator.comparing(Span::start)).toList();
        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            Span last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && !span.start().isAfter(last.end())) {
                merged.set(merged.size() - 1, new Span(last.start(), span.end().isAfter(last.end()) ? span.end() : last.end()));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    private static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private static LocalTime time(String value, String name) {
        if (value == null || value.isBlank()) {
            throw bad(name + " is required.");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(name + " must be a time such as 08:30.");
        }
    }

    private static LocalDate day(String value, String name) {
        if (value == null || value.isBlank()) {
            throw bad(name + " is required.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(name + " must be a date such as 2030-01-09.");
        }
    }

    private static OffsetDateTime instant(String value, String name) {
        if (value == null || value.isBlank()) {
            throw bad(name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(name + " must be a date and time with an offset, such as 2030-01-07T09:00:00+09:00.");
        }
    }

    /**
     * Why a version was saved (ADR-017 decision 1): text PostgreSQL can store, trimmed, none when blank, at most
     * {@link #MAX_REASON} characters.
     */
    private static String versionReason(String value) {
        StorableText.require(value, "reason");
        String reason = value == null || value.isBlank() ? null : value.trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters.");
        }
        return reason;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
