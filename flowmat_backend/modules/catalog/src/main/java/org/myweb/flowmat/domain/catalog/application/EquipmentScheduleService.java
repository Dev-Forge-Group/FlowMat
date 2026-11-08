package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentCalendarRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDayCopyRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDayRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDowntimeRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentAvailabilityResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentCalendarHistoryResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentCalendarVersionResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentDayHistoryResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentScheduleResponse;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWorkingTimeQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentCalendar;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayOverride;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDowntime;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentSchedule;
import org.myweb.flowmat.domain.catalog.domain.entity.ProjectHoliday;
import org.myweb.flowmat.domain.catalog.repository.EquipmentCalendarRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentDayOverrideRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentDowntimeRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentScheduleRepository;
import org.myweb.flowmat.domain.catalog.repository.ProjectHolidayRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment calendars and downtime, and how much working time they leave in a window (docs/domain/equipment-schedule.md).
 * Shifts are local times in the planning time zone; downtime is stored as instants. Calendars come in versions that apply
 * from a date, so changing or removing one leaves earlier dates as they were
 * (docs/architecture/adr/ADR-014-equipment-calendar-history.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentScheduleService implements EquipmentWorkingTimeQuery {

    private static final String NOT_DELETED = "N";
    private static final Set<String> DOWNTIME_TYPES = Set.of("maintenance", "breakdown", "other");
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final long SECONDS_PER_DAY = 86_400;
    private static final long SECONDS_PER_WEEK = 7 * SECONDS_PER_DAY;
    private static final int MAX_SHIFTS = 6;
    /** Dates one change can cover (D6). */
    private static final int MAX_RANGE_DAYS = 62;
    private static final List<String> DAY_NAMES = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final Comparator<EquipmentSchedule> EARLIEST_FIRST =
        Comparator.comparing(EquipmentSchedule::getEffectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final EquipmentRepository equipmentRepository;
    private final EquipmentCalendarRepository calendarRepository;
    private final EquipmentScheduleRepository scheduleRepository;
    private final EquipmentScheduleHistoryService history;
    private final EquipmentDayHistoryService dayChangeHistory;
    private final EquipmentDowntimeRepository downtimeRepository;
    private final EquipmentDayOverrideRepository dayOverrideRepository;
    private final ProjectHolidayRepository holidayRepository;
    private final ProjectAccess projectAccessService;
    private final IdGenerator idGenerator;

    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;

    /**
     * Working time in a window: hours with a calendar, hours down inside them, and what is left. {@code holidays} are the
     * project holidays whose shift would have fallen in the window (docs/domain/equipment-schedule.md "휴일").
     */
    public record Availability(boolean calendarSet, BigDecimal workingHours, BigDecimal downtimeHours, BigDecimal availableHours,
                               List<LocalDate> holidays) {
    }

    /**
     * The shifts in a window and the holidays that took a shift out of it; {@code calendarSet} when a date in the window has
     * a calendar. {@code worked} are the shifts on dates with a calendar; dates without one run around the clock and are in
     * {@code open} whole.
     */
    private record Shifts(List<Span> worked, List<Span> open, List<LocalDate> holidays, boolean calendarSet) {
        /** Every stretch, the shifts and the dates without a calendar, as they come. */
        List<Span> spans() {
            List<Span> all = new ArrayList<>(worked);
            all.addAll(open);
            return all;
        }
    }

    /** One calendar version, from {@code from} (null: from the start) until the next; no rows runs around the clock. */
    private record Version(String scheduleId, LocalDate from, List<EquipmentCalendar> rows) {
    }

    /** A requested shift, checked but not stored yet. */
    private record ShiftPlan(LocalTime start, LocalTime end, TreeSet<Integer> days) {
    }

    /** One of a date's own shifts. */
    private record DayShift(LocalTime start, LocalTime end) {
    }

    private record Span(Instant start, Instant end) {
        long seconds() {
            return Duration.between(start, end).getSeconds();
        }
    }

    public EquipmentScheduleResponse schedule(String equipmentId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return toResponse(equipment);
    }

    /**
     * Replaces all of the equipment's shifts (docs/domain/equipment-schedule.md "교대"). A request without {@code shifts} is
     * one shift, as before multi-shift calendars. Shifts may not overlap on any day, a night shift included. They apply from
     * {@code effectiveFrom}, today when left out and never earlier; saving again from the same date changes that version
     * (ADR-014).
     */
    @Transactional
    public EquipmentScheduleResponse setCalendar(String equipmentId, EquipmentCalendarRequest request) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shiftStart, shiftEnd and workDays are required.");
        }
        List<EquipmentCalendarRequest.Shift> requested = request.shifts() != null ? request.shifts()
            : List.of(new EquipmentCalendarRequest.Shift(request.shiftStart(), request.shiftEnd(), request.workDays()));
        if (requested.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Add at least one shift, or remove the calendar.");
        }
        if (requested.size() > MAX_SHIFTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A calendar can have at most " + MAX_SHIFTS + " shifts.");
        }
        List<ShiftPlan> plans = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            plans.add(plan(requested.get(index), requested.size() == 1 ? "" : "Shift " + (index + 1) + ": "));
        }
        requireNoOverlap(plans);
        writeVersion(equipment, effectiveFrom(equipment, request.effectiveFrom()), plans, versionReason(request.reason()));
        return toResponse(equipment);
    }

    /**
     * From {@code effectiveFrom} (today when left out) the equipment has no calendar and is available around the clock;
     * earlier dates keep the shifts they had (ADR-014 decision 2).
     */
    @Transactional
    public EquipmentScheduleResponse clearCalendar(String equipmentId, String effectiveFrom, String reason) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        writeVersion(equipment, effectiveFrom(equipment, effectiveFrom), List.of(), versionReason(reason));
        return toResponse(equipment);
    }

    /**
     * Makes these the equipment's shifts from {@code from}, none for around the clock; one version a day (ADR-014), with the
     * reason last given for it (ADR-017 decision 3).
     */
    private void writeVersion(Equipment equipment, LocalDate from, List<ShiftPlan> plans, String reason) {
        calendarRepository.lockCalendar(equipment.getEquipmentId());
        String user = projectAccessService.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        EquipmentSchedule schedule = scheduleRepository.findAllByEquipmentId(equipment.getEquipmentId()).stream()
            .filter(one -> from.equals(one.getEffectiveFrom()))
            .findFirst()
            .orElse(null);
        if (schedule == null) {
            schedule = new EquipmentSchedule();
            schedule.setScheduleId(idGenerator.generate());
            schedule.setEquipmentId(equipment.getEquipmentId());
            schedule.setEffectiveFrom(from);
        } else {
            calendarRepository.deleteAll(calendarRepository.findAllByScheduleIdOrderByShiftStartAscShiftIdAsc(schedule.getScheduleId()));
            calendarRepository.flush();
        }
        schedule.setReason(reason);
        schedule.setUpdatedBy(user);
        schedule.setUpdatedAt(now);
        scheduleRepository.saveAndFlush(schedule);
        List<EquipmentCalendar> saved = new ArrayList<>();
        for (ShiftPlan plan : plans) {
            EquipmentCalendar shift = new EquipmentCalendar();
            shift.setShiftId(idGenerator.generate());
            shift.setEquipmentId(equipment.getEquipmentId());
            shift.setScheduleId(schedule.getScheduleId());
            shift.setShiftStart(plan.start());
            shift.setShiftEnd(plan.end());
            shift.setWorkDays(plan.days().stream().map(String::valueOf).collect(Collectors.joining(",")));
            shift.setUpdatedBy(user);
            shift.setUpdatedAt(now);
            calendarRepository.save(shift);
            saved.add(shift);
        }
        // Each save stays in the history, also when it changes a version saved before (ADR-024).
        history.record(schedule, "saved", saved, user, now);
    }

    /** One version of the calendar (ADR-014); no shifts means the equipment runs around the clock from then. */
    public EquipmentCalendarVersionResponse calendarVersion(String equipmentId, String scheduleId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        EquipmentSchedule schedule = scheduleRepository.findByScheduleIdAndEquipmentId(scheduleId, equipment.getEquipmentId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        EquipmentSchedule current = currentOf(scheduleRepository.findAllByEquipmentId(equipment.getEquipmentId()).stream()
            .sorted(EARLIEST_FIRST).toList(), projectCalendar.today(equipment.getProjectId()));
        List<EquipmentCalendar> rows = calendarRepository.findAllByScheduleIdOrderByShiftStartAscShiftIdAsc(scheduleId);
        return new EquipmentCalendarVersionResponse(scheduleId, schedule.getEffectiveFrom(),
            current != null && current.getScheduleId().equals(scheduleId), rows.isEmpty(),
            rows.stream().map(EquipmentScheduleService::shiftLine).toList(), hours(weeklySeconds(rows)), schedule.getReason(),
            schedule.getUpdatedBy(), schedule.getUpdatedAt());
    }

    /** Every save and removal of the equipment's calendar versions, newest first, at most 100 (ADR-024). */
    public List<EquipmentCalendarHistoryResponse> calendarHistory(String equipmentId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return history.list(equipment.getEquipmentId());
    }

    /** Every change of the equipment's own dates and downtimes, newest first, at most 100 (ADR-024 decisions 7-12). */
    public List<EquipmentDayHistoryResponse> dayHistory(String equipmentId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return dayChangeHistory.list(equipment.getEquipmentId());
    }

    /** Deletes a calendar version that has not started (ADR-014, as ADR-013 decision 4); one that has started stays. */
    @Transactional
    public EquipmentScheduleResponse removeCalendarVersion(String equipmentId, String scheduleId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        calendarRepository.lockCalendar(equipment.getEquipmentId());
        EquipmentSchedule schedule = scheduleRepository.findByScheduleIdAndEquipmentId(scheduleId, equipment.getEquipmentId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (schedule.getEffectiveFrom() == null || !schedule.getEffectiveFrom().isAfter(projectCalendar.today(equipment.getProjectId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "This calendar version has started; save a new one from a later date instead.");
        }
        List<EquipmentCalendar> shifts = calendarRepository.findAllByScheduleIdOrderByShiftStartAscShiftIdAsc(scheduleId);
        // The removed version stays in the history with what it held (ADR-024).
        history.record(schedule, "removed", shifts, projectAccessService.requireCurrentUserId(), OffsetDateTime.now());
        calendarRepository.deleteAll(shifts);
        calendarRepository.flush();
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();
        return toResponse(equipment);
    }

    /**
     * Why a calendar version was saved (ADR-017 decision 1): text PostgreSQL can store, trimmed, none when blank, at most 200
     * characters as for a date.
     */
    private static String versionReason(String value) {
        StorableText.require(value, "reason");
        String reason = value == null || value.isBlank() ? null : value.trim();
        if (reason != null && reason.length() > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reason can be at most 200 characters.");
        }
        return reason;
    }

    /** The date a saved version starts (ADR-014, as ADR-013 decisions 3 and 6): today when left out, never earlier. */
    private LocalDate effectiveFrom(Equipment equipment, String value) {
        LocalDate today = projectCalendar.today(equipment.getProjectId());
        if (value == null || value.isBlank()) {
            return today;
        }
        LocalDate from = date(value, "effectiveFrom");
        if (from.isBefore(today)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "effectiveFrom must be today (" + today + ") or later; change a past date with that date's own shifts.");
        }
        return from;
    }

    /** The equipment's calendar versions with their shifts, earliest first. */
    private List<Version> versions(String equipmentId) {
        Map<String, List<EquipmentCalendar>> rows = calendarRepository.findAllByEquipmentIdOrderByShiftStartAscShiftIdAsc(equipmentId)
            .stream().collect(Collectors.groupingBy(EquipmentCalendar::getScheduleId));
        return scheduleRepository.findAllByEquipmentId(equipmentId).stream()
            .sorted(EARLIEST_FIRST)
            .map(schedule -> new Version(schedule.getScheduleId(), schedule.getEffectiveFrom(),
                rows.getOrDefault(schedule.getScheduleId(), List.of())))
            .toList();
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

    private static EquipmentSchedule currentOf(List<EquipmentSchedule> schedules, LocalDate today) {
        EquipmentSchedule found = null;
        for (EquipmentSchedule schedule : schedules) {
            if (schedule.getEffectiveFrom() == null || !schedule.getEffectiveFrom().isAfter(today)) {
                found = schedule;
            }
        }
        return found;
    }

    /** Whether any of the dates has a calendar; a date's own shifts only stand in for a calendar's (D2). */
    private static boolean calendarOn(List<Version> versions, Collection<LocalDate> dates) {
        return dates.stream().anyMatch(day -> {
            Version version = versionOn(versions, day);
            return version != null && !version.rows().isEmpty();
        });
    }

    /** Whether the equipment has a calendar today or in a version still to come. */
    private static boolean calendarFrom(List<Version> versions, LocalDate today) {
        Version current = versionOn(versions, today);
        return (current != null && !current.rows().isEmpty())
            || versions.stream().anyMatch(version -> version.from() != null && version.from().isAfter(today) && !version.rows().isEmpty());
    }

    private static EquipmentScheduleResponse.Shift shiftLine(EquipmentCalendar row) {
        return new EquipmentScheduleResponse.Shift(row.getShiftId(), row.getShiftStart().toString(), row.getShiftEnd().toString(),
            List.copyOf(workDays(row)), hours(shiftLength(row.getShiftStart(), row.getShiftEnd()).getSeconds()));
    }

    private static long weeklySeconds(List<EquipmentCalendar> rows) {
        return rows.stream().mapToLong(row -> shiftLength(row.getShiftStart(), row.getShiftEnd()).getSeconds() * workDays(row).size()).sum();
    }

    private static ShiftPlan plan(EquipmentCalendarRequest.Shift shift, String prefix) {
        if (shift == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, prefix + "shiftStart, shiftEnd and workDays are required.");
        }
        try {
            LocalTime start = time(shift.shiftStart(), "shiftStart");
            LocalTime end = time(shift.shiftEnd(), "shiftEnd");
            if (shift.workDays() == null || shift.workDays().isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose at least one work day.");
            }
            TreeSet<Integer> days = new TreeSet<>();
            for (Integer day : shift.workDays()) {
                if (day == null || day < 1 || day > 7) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "workDays are ISO days of the week, 1 (Monday) to 7 (Sunday).");
                }
                days.add(day);
            }
            return new ShiftPlan(start, end, days);
        } catch (BusinessException exception) {
            throw prefix.isEmpty() ? exception : new BusinessException(ErrorCode.BAD_REQUEST, prefix + exception.getMessage());
        }
    }

    /**
     * Lays every shift on a week (a night shift from Sunday wraps into Monday) and refuses two shifts that share any
     * second, naming the day the overlap starts on. Shifts that only touch, such as 06:00-14:00 and 14:00-22:00, are fine.
     */
    private static void requireNoOverlap(List<ShiftPlan> plans) {
        record Piece(int shift, long start, long end) {
        }
        List<Piece> pieces = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            ShiftPlan plan = plans.get(index);
            long length = shiftLength(plan.start(), plan.end()).getSeconds();
            for (int day : plan.days()) {
                long start = (day - 1) * SECONDS_PER_DAY + plan.start().toSecondOfDay();
                long end = start + length;
                if (end <= SECONDS_PER_WEEK) {
                    pieces.add(new Piece(index, start, end));
                } else {
                    pieces.add(new Piece(index, start, SECONDS_PER_WEEK));
                    pieces.add(new Piece(index, 0, end - SECONDS_PER_WEEK));
                }
            }
        }
        for (Piece first : pieces) {
            for (Piece second : pieces) {
                long overlapStart = Math.max(first.start(), second.start());
                if (first.shift() < second.shift() && overlapStart < Math.min(first.end(), second.end())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Shifts " + (first.shift() + 1) + " and "
                        + (second.shift() + 1) + " overlap on " + DAY_NAMES.get((int) (overlapStart / SECONDS_PER_DAY)) + ".");
                }
            }
        }
    }

    /**
     * Sets the shifts that start on one date in place of the calendar's (docs/domain/equipment-schedule.md "날짜별 교대",
     * D1-D5): none closes the day, others shorten it or add work on a day off. Needs a calendar, since without one the
     * equipment is always available. Setting the date again replaces its shifts. With {@code throughDate} every date up to
     * it gets the same shifts (D6).
     */
    @Transactional
    public EquipmentScheduleResponse setDay(String equipmentId, String date, EquipmentDayRequest request) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        LocalDate day = date(date);
        LocalDate through = request == null || request.throughDate() == null || request.throughDate().isBlank()
            ? day : date(request.throughDate(), "throughDate");
        if (through.isBefore(day)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "throughDate must not be before the date.");
        }
        if (ChronoUnit.DAYS.between(day, through) >= MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        List<Integer> askedDays = request == null || request.weekDays() == null ? List.of() : request.weekDays();
        if (askedDays.stream().anyMatch(one -> one == null || one < 1 || one > 7)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "weekDays are 1 (Monday) to 7 (Sunday).");
        }
        Set<Integer> weekDays = Set.copyOf(askedDays);
        List<LocalDate> dates = day.datesUntil(through.plusDays(1))
            .filter(one -> weekDays.isEmpty() || weekDays.contains(one.getDayOfWeek().getValue()))
            .toList();
        if (dates.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "None of the dates fall on the chosen days of the week.");
        }
        if (!calendarOn(versions(equipment.getEquipmentId()), dates)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Set the equipment's calendar first; a date's shifts take the place of the calendar's.");
        }
        List<EquipmentDayRequest.Shift> requested = request == null || request.shifts() == null ? List.of() : request.shifts();
        if (requested.size() > MAX_SHIFTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A day can have at most " + MAX_SHIFTS + " shifts.");
        }
        List<DayShift> shifts = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            String prefix = requested.size() == 1 ? "" : "Shift " + (index + 1) + ": ";
            EquipmentDayRequest.Shift shift = requested.get(index);
            if (shift == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, prefix + "shiftStart and shiftEnd are required.");
            }
            try {
                shifts.add(new DayShift(time(shift.shiftStart(), "shiftStart").withSecond(0).withNano(0),
                    time(shift.shiftEnd(), "shiftEnd").withSecond(0).withNano(0)));
            } catch (BusinessException exception) {
                throw prefix.isEmpty() ? exception : new BusinessException(ErrorCode.BAD_REQUEST, prefix + exception.getMessage());
            }
        }
        requireNoDayOverlap(shifts);
        StorableText.require(request == null ? null : request.reason(), "reason");
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (reason != null && reason.length() > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reason can be at most 200 characters.");
        }
        String text = shifts.stream()
            .map(shift -> HOUR_MINUTE.format(shift.start()) + "-" + HOUR_MINUTE.format(shift.end()))
            .collect(Collectors.joining(","));
        saveDays(equipment, dates.stream().map(one -> new DayChange(one, text, reason)).toList(), reason, null);
        return toResponse(equipment);
    }

    /** The date goes back to the calendar's shifts; with {@code through}, every date of its own up to that one (D6). */
    @Transactional
    public EquipmentScheduleResponse clearDay(String equipmentId, String date, String through) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        LocalDate day = date(date);
        LocalDate last = through == null || through.isBlank() ? day : date(through, "through");
        if (last.isBefore(day)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "through must not be before the date.");
        }
        List<EquipmentDayOverride> found = dayOverrideRepository.findAllByEquipmentIdAndOverrideDateBetween(equipment.getEquipmentId(),
            day, last);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // The history keeps what each date had (ADR-024).
        dayChangeHistory.recordDays(equipment.getEquipmentId(), "removed", found, null, null,
            projectAccessService.requireCurrentUserId(), OffsetDateTime.now());
        dayOverrideRepository.deleteAll(found);
        return toResponse(equipment);
    }

    /**
     * Copies this equipment's own dates (from {@code fromDate} through {@code throughDate}, either side open) to another
     * equipment of the project, replacing what those dates had there; its other dates stay (D7). That equipment needs a
     * calendar, as for a date of its own (D2).
     */
    @Transactional
    public EquipmentScheduleResponse copyDays(String equipmentId, EquipmentDayCopyRequest request) {
        Equipment source = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(source.getProjectId());
        String targetId = request == null || request.toEquipmentId() == null ? "" : request.toEquipmentId().trim();
        if (targetId.equals(source.getEquipmentId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose another equipment to copy to.");
        }
        Equipment target = equipmentRepository.findByEquipmentIdAndDeletedYn(targetId, NOT_DELETED)
            .filter(found -> source.getProjectId().equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "toEquipmentId is not an equipment of this project."));
        LocalDate from = request.fromDate() == null || request.fromDate().isBlank() ? null : date(request.fromDate(), "fromDate");
        LocalDate through = request.throughDate() == null || request.throughDate().isBlank()
            ? null : date(request.throughDate(), "throughDate");
        if (from != null && through != null && through.isBefore(from)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "throughDate must not be before fromDate.");
        }
        if (!calendarFrom(versions(target.getEquipmentId()), projectCalendar.today(target.getProjectId()))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Set the calendar of " + label(target) + " first; a date's shifts take the place of the calendar's.");
        }
        List<DayChange> changes = dayOverrideRepository.findAllByEquipmentIdOrderByOverrideDateAsc(source.getEquipmentId()).stream()
            .filter(day -> from == null || !day.getOverrideDate().isBefore(from))
            .filter(day -> through == null || !day.getOverrideDate().isAfter(through))
            .map(day -> new DayChange(day.getOverrideDate(), day.getShifts(), day.getReason()))
            .toList();
        if (changes.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "There are no day changes to copy.");
        }
        saveDays(target, changes, null, source.getEquipmentId());
        return toResponse(target);
    }

    /** One date's own shifts as stored ("06:00-14:00,14:00-18:00", empty when closed). */
    private record DayChange(LocalDate date, String shifts, String reason) {
    }

    /**
     * Writes each date's own shifts, replacing what the date had (D1); {@code changes} run from the earliest date. The history
     * keeps the save with the {@code reason} it was given, or the equipment it was {@code copiedFrom} (ADR-024).
     */
    private void saveDays(Equipment equipment, List<DayChange> changes, String reason, String copiedFrom) {
        Map<LocalDate, EquipmentDayOverride> existing = dayOverrideRepository
            .findAllByEquipmentIdAndOverrideDateBetween(equipment.getEquipmentId(), changes.get(0).date(),
                changes.get(changes.size() - 1).date()).stream()
            .collect(Collectors.toMap(EquipmentDayOverride::getOverrideDate, override -> override));
        String actor = projectAccessService.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        List<EquipmentDayOverride> saved = new ArrayList<>();
        for (DayChange change : changes) {
            EquipmentDayOverride override = existing.get(change.date());
            if (override == null) {
                override = new EquipmentDayOverride();
                override.setOverrideId(idGenerator.generate());
                override.setEquipmentId(equipment.getEquipmentId());
                override.setOverrideDate(change.date());
            }
            override.setShifts(change.shifts());
            override.setReason(change.reason());
            override.setUpdatedBy(actor);
            override.setUpdatedAt(now);
            saved.add(override);
        }
        dayOverrideRepository.saveAll(saved);
        dayChangeHistory.recordDays(equipment.getEquipmentId(), "saved", saved, reason, copiedFrom, actor, now);
    }

    private static String label(Equipment equipment) {
        return equipment.getEquipmentCode() != null ? equipment.getEquipmentCode() : equipment.getEquipmentName();
    }

    /** Refuses two of a date's shifts that share any second (a night shift runs into the next morning). */
    private static void requireNoDayOverlap(List<DayShift> shifts) {
        for (int first = 0; first < shifts.size(); first++) {
            for (int second = first + 1; second < shifts.size(); second++) {
                long firstStart = shifts.get(first).start().toSecondOfDay();
                long secondStart = shifts.get(second).start().toSecondOfDay();
                long firstEnd = firstStart + shiftLength(shifts.get(first).start(), shifts.get(first).end()).getSeconds();
                long secondEnd = secondStart + shiftLength(shifts.get(second).start(), shifts.get(second).end()).getSeconds();
                if (Math.max(firstStart, secondStart) < Math.min(firstEnd, secondEnd)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Shifts " + (first + 1) + " and " + (second + 1) + " overlap.");
                }
            }
        }
    }

    @Transactional
    public EquipmentScheduleResponse addDowntime(String equipmentId, EquipmentDowntimeRequest request) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "startsAt and endsAt are required.");
        }
        String type = request.downtimeType() == null || request.downtimeType().isBlank()
            ? "maintenance"
            : request.downtimeType().trim().toLowerCase(Locale.ROOT);
        if (!DOWNTIME_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "downtimeType is maintenance, breakdown or other.");
        }
        OffsetDateTime startsAt = dateTime(request.startsAt(), "startsAt");
        OffsetDateTime endsAt = dateTime(request.endsAt(), "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "endsAt must be after startsAt.");
        }
        if (Duration.between(startsAt, endsAt).compareTo(LONGEST_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A downtime can be at most 366 days long.");
        }
        StorableText.require(request.reason(), "reason");
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (reason != null && reason.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reason can be at most 500 characters.");
        }
        EquipmentDowntime downtime = new EquipmentDowntime();
        downtime.setDowntimeId(idGenerator.generate());
        downtime.setProjectId(equipment.getProjectId());
        downtime.setEquipmentId(equipment.getEquipmentId());
        downtime.setDowntimeType(type);
        downtime.setStartsAt(startsAt);
        downtime.setEndsAt(endsAt);
        downtime.setReason(reason);
        downtime.setCreatedBy(projectAccessService.requireCurrentUserId());
        downtimeRepository.save(downtime);
        dayChangeHistory.recordDowntime(downtime, "saved", downtime.getCreatedBy(), OffsetDateTime.now());
        return toResponse(equipment);
    }

    @Transactional
    public EquipmentScheduleResponse removeDowntime(String equipmentId, String downtimeId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        EquipmentDowntime downtime = downtimeRepository.findByDowntimeIdAndDeletedYn(downtimeId, NOT_DELETED)
            .filter(found -> equipment.getEquipmentId().equals(found.getEquipmentId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        downtime.setDeletedYn("Y");
        downtime.setUpdatedBy(projectAccessService.requireCurrentUserId());
        downtimeRepository.save(downtime);
        dayChangeHistory.recordDowntime(downtime, "removed", downtime.getUpdatedBy(), OffsetDateTime.now());
        return toResponse(equipment);
    }

    public EquipmentAvailabilityResponse availability(String equipmentId, String from, String to) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        OffsetDateTime start = dateTime(from, "from");
        OffsetDateTime end = dateTime(to, "to");
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to must be after from.");
        }
        if (Duration.between(start, end).compareTo(LONGEST_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The window can be at most 366 days long.");
        }
        Availability availability = window(equipment, start, end);
        BigDecimal rate = equipment.getCapacityPerHour();
        BigDecimal capacity = rate == null ? null : availability.availableHours().multiply(rate).stripTrailingZeros();
        return new EquipmentAvailabilityResponse(equipment.getEquipmentId(), start, end, availability.calendarSet(),
            availability.workingHours(), availability.downtimeHours(), availability.availableHours(), rate, capacity,
            availability.holidays());
    }

    /**
     * Working time in [from, to) with no access check (callers check it): each shift that overlaps the window, clipped
     * to it, or the whole window without a calendar, less the merged downtime inside those shifts. The caller keeps the
     * window at most {@link #LONGEST_WINDOW} long.
     */
    public Availability window(Equipment equipment, OffsetDateTime from, OffsetDateTime to) {
        Instant start = from.toInstant();
        Instant end = to.toInstant();
        Shifts shifts = shifts(versions(equipment.getEquipmentId()), equipment, start, end);
        boolean calendarSet = shifts.calendarSet();
        List<Span> working = calendarSet ? merge(shifts.spans()) : List.of(new Span(start, end));
        List<Span> down = merge(downtimeRepository.findOverlapping(equipment.getEquipmentId(), from, to).stream()
            .map(downtime -> clip(downtime.getStartsAt().toInstant(), downtime.getEndsAt().toInstant(), start, end))
            .filter(span -> span != null)
            .toList());
        long workingSeconds = working.stream().mapToLong(Span::seconds).sum();
        long downSeconds = 0;
        for (Span shift : working) {
            for (Span downtime : down) {
                Span overlap = clip(downtime.start(), downtime.end(), shift.start(), shift.end());
                if (overlap != null) {
                    downSeconds += overlap.seconds();
                }
            }
        }
        return new Availability(calendarSet, hours(workingSeconds), hours(downSeconds), hours(workingSeconds - downSeconds),
            shifts.holidays());
    }

    /**
     * The earliest stretch from {@code from} that holds {@code seconds} of available time (docs/domain/equipment-schedule.md
     * "계획 기간 제안"): it starts at the first available moment at or after {@code from} and ends once the available time
     * since then adds up, skipping time off shift, holidays and downtime. Empty when the {@link #LONGEST_WINDOW} from
     * {@code from} does not hold that much. No access check; callers check it.
     */
    public Optional<EquipmentSlot> earliestSlot(Equipment equipment, OffsetDateTime from, long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds must be positive.");
        }
        ZoneId zone = zone(equipment.getProjectId());
        Instant first = null;
        long left = seconds;
        for (Span span : available(equipment, from, from.plus(LONGEST_WINDOW))) {
            if (first == null) {
                first = span.start();
            }
            if (span.seconds() >= left) {
                return Optional.of(new EquipmentSlot(first.atZone(zone).toOffsetDateTime(),
                    span.start().plusSeconds(left).atZone(zone).toOffsetDateTime()));
            }
            left -= span.seconds();
        }
        return Optional.empty();
    }

    /**
     * When a delayed step may start on the equipment in [from, to) (docs/architecture/adr/ADR-015-shift-aware-delay.md decisions
     * 2 and 4): on dates with a calendar its available time, shifts less holidays and downtime; the whole of each date without
     * one, downtime or not. Earliest first and none overlapping, worked out once for the window so callers can intersect it
     * with people's time (ADR-016).
     */
    @Override
    public Optional<List<EquipmentSlot>> workingStretches(String equipmentId, OffsetDateTime from, OffsetDateTime to) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED).map(equipment -> {
            ZoneId zone = zone(equipment.getProjectId());
            Instant start = from.toInstant();
            Instant end = to.toInstant();
            Shifts shifts = shifts(versions(equipment.getEquipmentId()), equipment, start, end);
            List<Span> stretches;
            if (!shifts.calendarSet()) {
                stretches = List.of(new Span(start, end));
            } else {
                List<Span> all = new ArrayList<>(lessDowntime(merge(shifts.worked()), equipment, from, to));
                all.addAll(shifts.open());
                stretches = merge(all);
            }
            return stretches.stream()
                .map(span -> new EquipmentSlot(span.start().atZone(zone).toOffsetDateTime(), span.end().atZone(zone).toOffsetDateTime()))
                .toList();
        });
    }

    /** {@link #available} in the planning time zone, for planning that also follows people (worker-calendar.md WC24). */
    public List<EquipmentSlot> availableSlots(Equipment equipment, OffsetDateTime from, OffsetDateTime to) {
        ZoneId zone = zone(equipment.getProjectId());
        return available(equipment, from, to).stream()
            .map(span -> new EquipmentSlot(span.start().atZone(zone).toOffsetDateTime(), span.end().atZone(zone).toOffsetDateTime()))
            .toList();
    }

    /** The shifts in [from, to) (all of it without a calendar) less downtime: earliest first, none overlapping. */
    private List<Span> available(Equipment equipment, OffsetDateTime from, OffsetDateTime to) {
        Instant start = from.toInstant();
        Instant end = to.toInstant();
        Shifts shifts = shifts(versions(equipment.getEquipmentId()), equipment, start, end);
        List<Span> working = shifts.calendarSet() ? merge(shifts.spans()) : List.of(new Span(start, end));
        return lessDowntime(working, equipment, from, to);
    }

    /** The working stretches less the equipment's downtime in [from, to): earliest first, none overlapping. */
    private List<Span> lessDowntime(List<Span> working, Equipment equipment, OffsetDateTime from, OffsetDateTime to) {
        Instant start = from.toInstant();
        Instant end = to.toInstant();
        List<Span> down = merge(downtimeRepository.findOverlapping(equipment.getEquipmentId(), from, to).stream()
            .map(downtime -> clip(downtime.getStartsAt().toInstant(), downtime.getEndsAt().toInstant(), start, end))
            .filter(span -> span != null)
            .toList());
        List<Span> free = new ArrayList<>();
        for (Span shift : working) {
            Instant cursor = shift.start();
            for (Span downtime : down) {
                if (!downtime.start().isBefore(shift.end())) {
                    break;
                }
                if (downtime.end().isAfter(cursor)) {
                    if (downtime.start().isAfter(cursor)) {
                        free.add(new Span(cursor, downtime.start()));
                    }
                    cursor = downtime.end();
                }
            }
            if (cursor.isBefore(shift.end())) {
                free.add(new Span(cursor, shift.end()));
            }
        }
        return free;
    }

    /**
     * Each shift on each of its work days, from the day before the window (a shift can run past midnight into it). No
     * shift starts on a project holiday; a night shift that starts the day before a holiday still runs into it. A holiday
     * is listed once even when it took out several shifts. A date with its own shifts uses those instead of the
     * calendar's, holiday or not (D4). Each date takes the shifts of the version that applies on it; a date without a
     * calendar runs around the clock, with no holidays or own shifts (ADR-014 decisions 2, 3, 5).
     */
    private Shifts shifts(List<Version> versions, Equipment equipment, Instant start, Instant end) {
        if (versions.stream().allMatch(version -> version.rows().isEmpty())) {
            return new Shifts(List.of(), List.of(), List.of(), false);
        }
        ZoneId zone = zone(equipment.getProjectId());
        LocalDate first = start.atZone(zone).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(zone).toLocalDate();
        Set<LocalDate> holidays = holidayRepository.findAllByProjectIdAndHolidayDateBetweenAndDeletedYn(equipment.getProjectId(),
                first, last, NOT_DELETED)
            .stream()
            .map(ProjectHoliday::getHolidayDate)
            .collect(Collectors.toSet());
        Map<LocalDate, List<DayShift>> ownShifts = dayOverrideRepository
            .findAllByEquipmentIdAndOverrideDateBetween(equipment.getEquipmentId(), first, last).stream()
            .collect(Collectors.toMap(EquipmentDayOverride::getOverrideDate, override -> dayShifts(override.getShifts())));
        List<Span> shifts = new ArrayList<>();
        List<Span> open = new ArrayList<>();
        TreeSet<LocalDate> skipped = new TreeSet<>();
        boolean set = false;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            Version version = versionOn(versions, day);
            if (version == null || version.rows().isEmpty()) {
                Span whole = clip(dayStart, dayEnd, start, end);
                if (whole != null) {
                    open.add(whole);
                }
                continue;
            }
            set |= dayStart.isBefore(end) && dayEnd.isAfter(start);
            List<DayShift> own = ownShifts.get(day);
            if (own != null) {
                for (DayShift shift : own) {
                    Span span = span(day, shift.start(), shift.end(), zone, start, end);
                    if (span != null) {
                        shifts.add(span);
                    }
                }
                continue;
            }
            for (EquipmentCalendar row : version.rows()) {
                if (!workDays(row).contains(day.getDayOfWeek().getValue())) {
                    continue;
                }
                Span shift = span(day, row.getShiftStart(), row.getShiftEnd(), zone, start, end);
                if (shift == null) {
                    continue;
                }
                if (holidays.contains(day)) {
                    skipped.add(day);
                } else {
                    shifts.add(shift);
                }
            }
        }
        return new Shifts(shifts, open, List.copyOf(skipped), set);
    }

    /** A shift that starts on the day, clipped to [start, end); null when it falls outside. */
    private static Span span(LocalDate day, LocalTime from, LocalTime to, ZoneId zone, Instant start, Instant end) {
        LocalDateTime shiftStart = day.atTime(from);
        return clip(shiftStart.atZone(zone).toInstant(), shiftStart.plus(shiftLength(from, to)).atZone(zone).toInstant(), start, end);
    }

    /** "06:00-14:00,14:00-18:00" as shifts; empty for a closed day. */
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

    private EquipmentScheduleResponse toResponse(Equipment equipment) {
        List<EquipmentSchedule> schedules = scheduleRepository.findAllByEquipmentId(equipment.getEquipmentId()).stream()
            .sorted(EARLIEST_FIRST).toList();
        EquipmentSchedule current = currentOf(schedules, projectCalendar.today(equipment.getProjectId()));
        // The calendar shown is the version of today (ADR-014); none when it runs around the clock.
        List<EquipmentCalendar> rows = current == null ? List.of()
            : calendarRepository.findAllByScheduleIdOrderByShiftStartAscShiftIdAsc(current.getScheduleId());
        Set<String> withShifts = calendarRepository.findAllByEquipmentIdOrderByShiftStartAscShiftIdAsc(equipment.getEquipmentId()).stream()
            .map(EquipmentCalendar::getScheduleId).collect(Collectors.toSet());
        List<EquipmentScheduleResponse.CalendarVersion> versions = schedules.stream()
            .map(schedule -> new EquipmentScheduleResponse.CalendarVersion(schedule.getScheduleId(), schedule.getEffectiveFrom(),
                schedule == current, !withShifts.contains(schedule.getScheduleId()), schedule.getReason(), schedule.getUpdatedBy(),
                schedule.getUpdatedAt()))
            .toList();
        EquipmentScheduleResponse.Calendar calendar = null;
        if (!rows.isEmpty()) {
            // The shifts are replaced together, so they share who and when.
            calendar = new EquipmentScheduleResponse.Calendar(rows.stream().map(EquipmentScheduleService::shiftLine).toList(),
                hours(weeklySeconds(rows)), rows.get(0).getUpdatedBy(), rows.get(0).getUpdatedAt());
        }
        List<EquipmentScheduleResponse.Downtime> downtimes = downtimeRepository
            .findAllByEquipmentIdAndDeletedYnOrderByStartsAtDesc(equipment.getEquipmentId(), NOT_DELETED).stream()
            .map(downtime -> new EquipmentScheduleResponse.Downtime(downtime.getDowntimeId(), downtime.getDowntimeType(),
                downtime.getStartsAt(), downtime.getEndsAt(),
                hours(Duration.between(downtime.getStartsAt(), downtime.getEndsAt()).getSeconds()), downtime.getReason(),
                downtime.getCreatedBy(), downtime.getCreatedAt()))
            .toList();
        List<EquipmentScheduleResponse.Day> days = dayOverrideRepository.findAllByEquipmentIdOrderByOverrideDateAsc(equipment.getEquipmentId())
            .stream()
            .map(override -> {
                List<DayShift> own = dayShifts(override.getShifts());
                List<EquipmentScheduleResponse.DayShift> shifts = own.stream()
                    .map(shift -> new EquipmentScheduleResponse.DayShift(HOUR_MINUTE.format(shift.start()), HOUR_MINUTE.format(shift.end()),
                        hours(shiftLength(shift.start(), shift.end()).getSeconds())))
                    .toList();
                long seconds = own.stream().mapToLong(shift -> shiftLength(shift.start(), shift.end()).getSeconds()).sum();
                return new EquipmentScheduleResponse.Day(override.getOverrideDate().toString(), shifts, shifts.isEmpty(), hours(seconds),
                    override.getReason(), override.getUpdatedBy(), override.getUpdatedAt());
            })
            .toList();
        return new EquipmentScheduleResponse(equipment.getEquipmentId(), zone(equipment.getProjectId()).getId(), calendar, downtimes, days,
            versions);
    }

    private Equipment findEquipment(String equipmentId) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ZoneId zone(String projectId) {
        return projectCalendar.zone(projectId);
    }

    private static TreeSet<Integer> workDays(EquipmentCalendar calendar) {
        TreeSet<Integer> days = new TreeSet<>();
        for (String day : calendar.getWorkDays().split(",")) {
            days.add(Integer.parseInt(day.trim()));
        }
        return days;
    }

    /** A shift that ends at or before its start runs into the next day; equal times are a whole day. */
    private static Duration shiftLength(LocalTime start, LocalTime end) {
        Duration length = Duration.between(start, end);
        return length.isNegative() || length.isZero() ? length.plusHours(24) : length;
    }

    /** The part of [start, end) inside [from, to), or null when they do not overlap. */
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required.");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be a time such as 08:30.");
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "date must be a date such as 2030-01-09.");
        }
    }

    private static LocalDate date(String value, String name) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be a date such as 2030-01-09.");
        }
    }

    private static OffsetDateTime dateTime(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                name + " must be a date and time with an offset, such as 2030-01-07T09:00:00+09:00.");
        }
    }
}
