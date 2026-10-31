package org.myweb.flowmat.domain.workforce.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.ProjectHolidayQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.response.WorkerAttendancePayResponse;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRateQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerLaborRates;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerPayQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.domain.workforce.domain.entity.LaborPremiumRule;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerAttendance;
import org.myweb.flowmat.domain.workforce.repository.WorkerAttendanceRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A worker's attendance as pay (docs/architecture/adr/ADR-028-pay-premiums.md), for the project owner only: each work day's
 * attended minutes, the overtime, night and holiday minutes its rule adds to, and the amount at that day's hourly rate. Premiums
 * add up where they meet, so night overtime gets both. Worked out when read; nothing is stored. Labor cost reads the same work
 * days (decision 10), and for worked time with clock times the same minutes cut where the premiums change (decision 24).
 */
@Service
@RequiredArgsConstructor
public class WorkerAttendancePayService implements WorkerPayQuery {
    private static final long MAX_RANGE_DAYS = 62;
    private static final long DEFAULT_DAYS = 7;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final WorkerAttendanceRepository attendance;
    private final WorkerLaborRateQuery rates;
    private final LaborPremiumService premiums;
    private final ProjectAccessService access;
    private final ProjectMemberQuery members;
    private final ProjectCalendarQuery calendars;
    private final ProjectHolidayQuery holidays;
    private final WorkerCalendarQuery calendar;

    /** Work days from {@code from} to {@code to} (project dates, as for attendance: 7 days to today by default, 62 at most). */
    @Transactional(readOnly = true)
    public WorkerAttendancePayResponse pay(String projectId, String userId, String from, String to) {
        access.requireProjectOwnerAccess(projectId);
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
        LocalDate today = calendars.today(projectId);
        LocalDate last = to == null || to.isBlank() ? today : day(to, "to");
        LocalDate first = from == null || from.isBlank() ? last.minusDays(DEFAULT_DAYS - 1) : day(from, "from");
        if (last.isBefore(first)) {
            throw bad("to must not be before from.");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) {
            throw bad("A range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        WorkerLaborRates rateBook = rates.rates(projectId, List.of(userId));
        List<WorkerAttendancePayResponse.Day> days = new ArrayList<>();
        for (Worked day : worked(projectId, userId, first, last)) {
            BigDecimal rate = rateBook.on(userId, day.date()).orElse(null);
            BigDecimal amount = rate == null ? null : day.paid().multiply(rate).divide(MINUTES_PER_HOUR, 4, RoundingMode.HALF_UP);
            days.add(new WorkerAttendancePayResponse.Day(day.date(), day.attended(), day.overtime(), day.weeklyOvertime(),
                day.holidayOvertime(), day.night(), day.holiday(), day.restDay(), day.premium(), day.paid(), day.ruleId(), rate, amount));
        }
        return new WorkerAttendancePayResponse(projectId, userId, first, last, List.copyOf(days),
            days.stream().mapToInt(WorkerAttendancePayResponse.Day::attendedMinutes).sum(),
            days.stream().map(WorkerAttendancePayResponse.Day::premiumMinutes).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
            days.stream().map(WorkerAttendancePayResponse.Day::paidMinutes).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
            days.stream().map(WorkerAttendancePayResponse.Day::amount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add),
            days.stream().allMatch(day -> day.amount() != null));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, PaidDay> paidDays(String projectId, String userId, LocalDate from, LocalDate to) {
        Map<LocalDate, PaidDay> days = new LinkedHashMap<>();
        for (Worked day : worked(projectId, userId, from, to)) {
            days.put(day.date(), new PaidDay(day.attended(), day.paid()));
        }
        return days;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PremiumSpan> premiumSpans(String projectId, String userId, LocalDate from, LocalDate to) {
        ZoneId zone = calendars.zone(projectId);
        LaborPremiumService.Rules rules = premiums.inForce(projectId, userId);
        Map<LocalDate, Worked> days = new HashMap<>();
        worked(projectId, userId, from, to).forEach(day -> days.put(day.date(), day));
        // The seconds of each work day's attendance before a stretch, as its overtime is its latest minutes (decision 24).
        Map<LocalDate, long[]> before = new HashMap<>();
        List<PremiumSpan> spans = new ArrayList<>();
        for (WorkerAttendance row : attendance.findAllByProjectIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAscAttendanceIdAsc(
                projectId, userId, from.atStartOfDay(zone).toOffsetDateTime(), to.plusDays(1).atStartOfDay(zone).toOffsetDateTime())) {
            Worked day = row.isCancelled() || row.getClockOut() == null ? null : days.get(row.getWorkDate());
            // Whole minutes, as the day's attended minutes count them.
            long whole = day == null ? 0 : Duration.between(row.getClockIn(), row.getClockOut()).toMinutes() * 60;
            if (whole <= 0) {
                continue;
            }
            long[] elapsed = before.computeIfAbsent(day.date(), date -> new long[1]);
            Instant in = row.getClockIn().toInstant();
            spans.addAll(cut(day, rules.on(day.date()).orElse(null), in, in.plusSeconds(whole), elapsed[0], zone));
            elapsed[0] += whole;
        }
        return spans;
    }

    /**
     * One stretch of a work day cut where its premiums change (decision 24). In the day's clock order its attended minutes are plain
     * up to its regular minutes less the weekly overtime, then weekly overtime, then daily or holiday overtime (decisions 3, 14, 18);
     * the night percent adds inside the night window, and a holiday, or a rest day paid like one, adds the holiday percent throughout.
     */
    private static List<PremiumSpan> cut(Worked day, LaborPremiumRule rule, Instant in, Instant out, long before, ZoneId zone) {
        long regular = (long) (day.attended() - day.overtime() - day.holidayOvertime()) * 60;
        long plain = regular - (long) day.weeklyOvertime() * 60;
        TreeSet<Instant> cuts = new TreeSet<>(List.of(in, out));
        addCut(cuts, in.plusSeconds(plain - before), in, out);
        addCut(cuts, in.plusSeconds(regular - before), in, out);
        if (rule != null && rule.getNightStart() != null) {
            for (LocalDate date = in.atZone(zone).toLocalDate().minusDays(1); !date.isAfter(out.atZone(zone).toLocalDate());
                 date = date.plusDays(1)) {
                addCut(cuts, nightStart(rule, date, zone), in, out);
                addCut(cuts, nightEnd(rule, date, zone), in, out);
            }
        }
        List<PremiumSpan> spans = new ArrayList<>();
        Instant from = null;
        for (Instant at : cuts) {
            if (from != null) {
                int percent = percentAt(day, rule, before + Duration.between(in, from).getSeconds(), plain, regular,
                    night(rule, from, zone));
                PremiumSpan last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
                if (last != null && last.percent() == percent) {
                    spans.set(spans.size() - 1, new PremiumSpan(day.date(), last.from(), at, percent));
                } else {
                    spans.add(new PremiumSpan(day.date(), from, at, percent));
                }
            }
            from = at;
        }
        return spans;
    }

    private static void addCut(TreeSet<Instant> cuts, Instant at, Instant in, Instant out) {
        if (at.isAfter(in) && at.isBefore(out)) {
            cuts.add(at);
        }
    }

    /** What the rule adds {@code position} seconds into the day's attendance (decision 24). */
    private static int percentAt(Worked day, LaborPremiumRule rule, long position, long plain, long regular, boolean night) {
        if (rule == null) {
            return 0;
        }
        int added = day.holiday() || day.restDay() ? percent(rule.getHolidayPercent()) : 0;
        if (night) {
            added += percent(rule.getNightPercent());
        }
        if (position >= regular) {
            added += percent(day.holidayOvertime() > 0 ? rule.getHolidayOvertimePercent() : rule.getOvertimePercent());
        } else if (position >= plain) {
            added += percent(rule.getWeeklyOvertimePercent());
        }
        return added;
    }

    /** Whether {@code at} is inside the night window that starts on its project date or the one before. */
    private static boolean night(LaborPremiumRule rule, Instant at, ZoneId zone) {
        if (rule == null || rule.getNightStart() == null) {
            return false;
        }
        LocalDate date = at.atZone(zone).toLocalDate();
        for (LocalDate day = date.minusDays(1); !day.isAfter(date); day = day.plusDays(1)) {
            if (!at.isBefore(nightStart(rule, day, zone)) && at.isBefore(nightEnd(rule, day, zone))) {
                return true;
            }
        }
        return false;
    }

    private static Instant nightStart(LaborPremiumRule rule, LocalDate date, ZoneId zone) {
        return date.atTime(rule.getNightStart()).atZone(zone).toInstant();
    }

    /** The end of the night window that starts on {@code date}: the next day when it ends earlier than it starts. */
    private static Instant nightEnd(LaborPremiumRule rule, LocalDate date, ZoneId zone) {
        return (rule.getNightEnd().isAfter(rule.getNightStart()) ? date : date.plusDays(1)).atTime(rule.getNightEnd()).atZone(zone)
            .toInstant();
    }

    /** A work day with attendance and what the premiums of its rule add (decisions 3-6). */
    private record Worked(LocalDate date, int attended, int overtime, int weeklyOvertime, int holidayOvertime, int night, boolean holiday,
                          boolean restDay, BigDecimal premium, BigDecimal paid, String ruleId) {
    }

    /** The work days from {@code first} to {@code last} that have attendance, oldest first. */
    private List<Worked> worked(String projectId, String userId, LocalDate first, LocalDate last) {
        ZoneId zone = calendars.zone(projectId);
        LaborPremiumService.Rules rules = premiums.inForce(projectId, userId);
        // Attended and night minutes of each work day's closed stretches that count, by the date of the clock-in (AT6), from six
        // days before the first so that its week counts whole (decision 14).
        Map<LocalDate, int[]> minutes = new TreeMap<>();
        for (WorkerAttendance row : attendance.findAllByProjectIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAscAttendanceIdAsc(
                projectId, userId, first.minusDays(6).atStartOfDay(zone).toOffsetDateTime(),
                last.plusDays(1).atStartOfDay(zone).toOffsetDateTime())) {
            if (row.isCancelled() || row.getClockOut() == null) {
                continue;
            }
            int[] day = minutes.computeIfAbsent(row.getWorkDate(), date -> new int[2]);
            day[0] += (int) Duration.between(row.getClockIn(), row.getClockOut()).toMinutes();
            LaborPremiumRule rule = rules.on(row.getWorkDate()).orElse(null);
            if (rule != null && rule.getNightStart() != null) {
                day[1] += nightMinutes(row.getClockIn().toInstant(), row.getClockOut().toInstant(), rule.getNightStart(), rule.getNightEnd(),
                    zone);
            }
        }
        // Holidays and rest days from the first loaded day, as the regular minutes of a week need them (decisions 14, 18).
        Set<LocalDate> holidayDays = holidays.holidays(projectId, first.minusDays(6), last);
        Set<LocalDate> restDays = restDays(projectId, userId, first.minusDays(6), last, rules, zone);
        // Each loaded day's regular minutes, less its own overtime: weekly overtime counts only these (decision 14).
        Map<LocalDate, Integer> regular = new TreeMap<>();
        minutes.forEach((date, day) -> {
            LaborPremiumRule rule = rules.on(date).orElse(null);
            int[] overtime = overtimes(rule, day[0], holidayDays.contains(date) || restDay(rule, date, restDays));
            regular.put(date, day[0] - overtime[0] - overtime[1]);
        });
        List<Worked> days = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> entry : minutes.entrySet()) {
            int attended = entry.getValue()[0];
            LocalDate date = entry.getKey();
            if (attended == 0 || date.isBefore(first)) {
                continue;
            }
            LaborPremiumRule rule = rules.on(date).orElse(null);
            boolean holiday = holidayDays.contains(date);
            boolean restDay = !holiday && restDay(rule, date, restDays);
            int[] overtime = overtimes(rule, attended, holiday || restDay);
            int weekly = weeklyOvertime(rule, date, regular);
            int night = entry.getValue()[1];
            long added = (long) overtime[0] * percent(rule == null ? null : rule.getOvertimePercent())
                + (long) overtime[1] * percent(rule == null ? null : rule.getHolidayOvertimePercent())
                + (long) weekly * percent(rule == null ? null : rule.getWeeklyOvertimePercent())
                + (long) night * percent(rule == null ? null : rule.getNightPercent())
                + (holiday || restDay ? (long) attended * percent(rule == null ? null : rule.getHolidayPercent()) : 0);
            BigDecimal premium = BigDecimal.valueOf(added).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            days.add(new Worked(date, attended, overtime[0], weekly, overtime[1], night, holiday, restDay, premium,
                BigDecimal.valueOf(attended).add(premium), rule == null ? null : rule.getRuleId()));
        }
        return days;
    }

    /** A rest day the rule pays like a holiday (decision 16). */
    private static boolean restDay(LaborPremiumRule rule, LocalDate date, Set<LocalDate> restDays) {
        return rule != null && rule.isRestDaysAsHolidays() && restDays.contains(date);
    }

    /**
     * The days from {@code first} to {@code last} without any of the worker's scheduled time (decision 16): their shifts, days off and
     * own days, as their calendar has them. Read only when some day's rule pays rest days like holidays; none without shifts.
     */
    private Set<LocalDate> restDays(String projectId, String userId, LocalDate first, LocalDate last, LaborPremiumService.Rules rules,
                                    ZoneId zone) {
        boolean asked = false;
        for (LocalDate date = first; !asked && !date.isAfter(last); date = date.plusDays(1)) {
            asked = rules.on(date).map(LaborPremiumRule::isRestDaysAsHolidays).orElse(false);
        }
        Set<LocalDate> rest = new HashSet<>();
        if (!asked) {
            return rest;
        }
        WorkerTime time = calendar.workingTime(projectId, List.of(userId), first.atStartOfDay(zone).toOffsetDateTime(),
            last.plusDays(1).atStartOfDay(zone).toOffsetDateTime()).get(userId);
        if (time == null || !time.calendarSet()) {
            return rest;
        }
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (time.secondsBetween(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()) == 0) {
                rest.add(date);
            }
        }
        return rest;
    }

    /**
     * A work day's overtime minutes past its rule's start, {daily, holiday}: on a holiday, or a rest day paid like one, with holiday
     * overtime in the rule only that counts (decision 18), otherwise the daily overtime. 0 without either.
     */
    private static int[] overtimes(LaborPremiumRule rule, int attended, boolean holidayLike) {
        if (rule == null) {
            return new int[2];
        }
        if (holidayLike && rule.getHolidayOvertimeAfterMinutes() != null) {
            return new int[] {0, Math.max(0, attended - rule.getHolidayOvertimeAfterMinutes())};
        }
        return new int[] {rule.getOvertimeAfterMinutes() == null ? 0 : Math.max(0, attended - rule.getOvertimeAfterMinutes()), 0};
    }

    /**
     * Regular minutes of {@code date} past the weekly overtime start of its rule (decision 14): what its week, from the day the
     * rule's week starts on through {@code date}, has past the start, less what the days before already took. 0 without one.
     */
    private static int weeklyOvertime(LaborPremiumRule rule, LocalDate date, Map<LocalDate, Integer> regular) {
        if (rule == null || rule.getWeeklyOvertimeAfterMinutes() == null) {
            return 0;
        }
        LocalDate weekStart = date.minusDays((date.getDayOfWeek().getValue() - rule.getWeekStartsOn() + 7) % 7);
        int before = 0;
        for (LocalDate day = weekStart; day.isBefore(date); day = day.plusDays(1)) {
            before += regular.getOrDefault(day, 0);
        }
        int through = before + regular.getOrDefault(date, 0);
        int after = rule.getWeeklyOvertimeAfterMinutes();
        return Math.max(0, through - after) - Math.max(0, before - after);
    }

    /**
     * Whole minutes of [in, out) inside the night window of each project date, which runs past midnight when it ends earlier than
     * it starts.
     */
    static int nightMinutes(Instant in, Instant out, LocalTime start, LocalTime end, ZoneId zone) {
        long seconds = 0;
        LocalDate last = out.atZone(zone).toLocalDate();
        for (LocalDate date = in.atZone(zone).toLocalDate().minusDays(1); !date.isAfter(last); date = date.plusDays(1)) {
            Instant windowStart = date.atTime(start).atZone(zone).toInstant();
            Instant windowEnd = (end.isAfter(start) ? date : date.plusDays(1)).atTime(end).atZone(zone).toInstant();
            Instant from = in.isAfter(windowStart) ? in : windowStart;
            Instant to = out.isBefore(windowEnd) ? out : windowEnd;
            if (to.isAfter(from)) {
                seconds += Duration.between(from, to).getSeconds();
            }
        }
        return (int) (seconds / 60);
    }

    private static int percent(Integer value) {
        return value == null ? 0 : value;
    }

    private static LocalDate day(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a date like 2041-09-01.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
