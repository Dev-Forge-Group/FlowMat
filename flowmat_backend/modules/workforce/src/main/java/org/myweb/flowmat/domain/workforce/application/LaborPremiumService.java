package org.myweb.flowmat.domain.workforce.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.api.dto.request.LaborPremiumRuleRequest;
import org.myweb.flowmat.domain.workforce.api.dto.response.LaborPremiumRuleResponse;
import org.myweb.flowmat.domain.workforce.domain.entity.LaborPremiumRule;
import org.myweb.flowmat.domain.workforce.repository.LaborPremiumRuleRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project's pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md): overtime past some minutes of a work day, a night
 * window, project holidays and the regular minutes of a week past some minutes (decision 13), each a percent added to the hourly
 * rate. Only the project owner reads or changes them. A rule
 * applies from today or a later project date, so the pay of earlier dates never changes; saving the same start again replaces it,
 * and one removed before it started stays marked. A rule with no premiums stops them from its date. A worker can have premiums of
 * their own in their own history, which win over the project's until a save of theirs follows the project again (decisions 20-22).
 */
@Service
@RequiredArgsConstructor
public class LaborPremiumService {
    private static final int MAX_REASON = 200;
    private static final int MAX_MINUTES = 1440;
    private static final int MAX_WEEK_MINUTES = 10080;
    private static final int MAX_PERCENT = 500;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final LaborPremiumRuleRepository repository;
    private final ProjectAccess access;
    private final ProjectCalendarQuery calendars;
    private final ProjectMemberQuery members;
    private final IdGenerator ids;

    /**
     * The saves that count, each for the dates from its start until the next one (decision 1): a worker's own over the project's
     * (decision 20).
     */
    public static final class Rules {
        private final List<LaborPremiumRule> project;
        private final List<LaborPremiumRule> worker;

        private Rules(List<LaborPremiumRule> project, List<LaborPremiumRule> worker) {
            this.project = project;
            this.worker = worker;
        }

        /**
         * The rule on {@code date}: the worker's own that started last by then, unless it follows the project; otherwise the
         * project's. Empty before the first.
         */
        public Optional<LaborPremiumRule> on(LocalDate date) {
            Optional<LaborPremiumRule> own = latest(worker, date);
            return own.isPresent() && !own.get().isFollowProject() ? own : latest(project, date);
        }
    }

    @Transactional(readOnly = true)
    public LaborPremiumRuleResponse rules(String projectId) {
        access.requireProjectOwnerAccess(projectId);
        return response(projectId, null);
    }

    /** A worker's own premiums (decision 20), for the project owner only. */
    @Transactional(readOnly = true)
    public LaborPremiumRuleResponse workerRules(String projectId, String userId) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        return response(projectId, userId);
    }

    @Transactional
    public LaborPremiumRuleResponse setRule(String projectId, LaborPremiumRuleRequest request) {
        access.requireProjectOwnerAccess(projectId);
        return save(projectId, null, request);
    }

    /** A worker's own premiums from a date, or {@code followProject} to go back to the project's (decisions 20-21). */
    @Transactional
    public LaborPremiumRuleResponse setWorkerRule(String projectId, String userId, LaborPremiumRuleRequest request) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        return save(projectId, userId, request);
    }

    private LaborPremiumRuleResponse save(String projectId, String userId, LaborPremiumRuleRequest request) {
        if (request == null) {
            throw bad("Send the premiums as JSON; send {} to stop them from a date.");
        }
        boolean follow = Boolean.TRUE.equals(request.followProject());
        if (follow && userId == null) {
            throw bad("followProject is for a worker's own premiums.");
        }
        Integer after = request.overtimeAfterMinutes();
        Integer overtime = request.overtimePercent();
        if ((after == null) != (overtime == null)) {
            throw bad("Give overtimeAfterMinutes and overtimePercent together, or neither.");
        }
        if (after != null && (after < 1 || after > MAX_MINUTES)) {
            throw bad("overtimeAfterMinutes is whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        percent(overtime, "overtimePercent");
        LocalTime nightStart = clock(request.nightStart(), "nightStart");
        LocalTime nightEnd = clock(request.nightEnd(), "nightEnd");
        Integer night = request.nightPercent();
        boolean noNight = nightStart == null && nightEnd == null && night == null;
        if (!noNight && (nightStart == null || nightEnd == null || night == null)) {
            throw bad("Give nightStart, nightEnd and nightPercent together, or none of them.");
        }
        if (!noNight && nightStart.equals(nightEnd)) {
            throw bad("nightStart and nightEnd must differ; a window past midnight ends earlier than it starts.");
        }
        percent(night, "nightPercent");
        percent(request.holidayPercent(), "holidayPercent");
        Integer weeklyAfter = request.weeklyOvertimeAfterMinutes();
        Integer weekly = request.weeklyOvertimePercent();
        Integer weekStart = request.weekStartsOn();
        if ((weeklyAfter == null) != (weekly == null)) {
            throw bad("Give weeklyOvertimeAfterMinutes and weeklyOvertimePercent together, or neither.");
        }
        if (weeklyAfter != null && (weeklyAfter < 1 || weeklyAfter > MAX_WEEK_MINUTES)) {
            throw bad("weeklyOvertimeAfterMinutes is whole minutes from 1 to " + MAX_WEEK_MINUTES + ".");
        }
        percent(weekly, "weeklyOvertimePercent");
        if (weekStart != null && weeklyAfter == null) {
            throw bad("weekStartsOn goes with weekly overtime; leave it out without one.");
        }
        if (weekStart != null && (weekStart < 1 || weekStart > 7)) {
            throw bad("weekStartsOn is a day from 1 (Monday) to 7 (Sunday).");
        }
        boolean restDays = Boolean.TRUE.equals(request.restDaysAsHolidays());
        if (restDays && request.holidayPercent() == null) {
            throw bad("restDaysAsHolidays needs holidayPercent; it pays rest days like holidays.");
        }
        Integer holidayAfter = request.holidayOvertimeAfterMinutes();
        Integer holidayOvertime = request.holidayOvertimePercent();
        if ((holidayAfter == null) != (holidayOvertime == null)) {
            throw bad("Give holidayOvertimeAfterMinutes and holidayOvertimePercent together, or neither.");
        }
        if (holidayAfter != null && request.holidayPercent() == null) {
            throw bad("holidayOvertimeAfterMinutes needs holidayPercent; it adds to it past those minutes.");
        }
        if (holidayAfter != null && (holidayAfter < 1 || holidayAfter > MAX_MINUTES)) {
            throw bad("holidayOvertimeAfterMinutes is whole minutes from 1 to " + MAX_MINUTES + ".");
        }
        percent(holidayOvertime, "holidayOvertimePercent");
        if (follow && (after != null || nightStart != null || nightEnd != null || night != null || request.holidayPercent() != null
                || weeklyAfter != null || weekly != null || weekStart != null || restDays || holidayAfter != null)) {
            throw bad("followProject leaves every premium empty; it returns the worker to the project's premiums.");
        }
        LocalDate today = calendars.today(projectId);
        String from = request.effectiveFrom();
        LocalDate start;
        try {
            start = from == null || from.isBlank() ? today : LocalDate.parse(from.trim());
        } catch (DateTimeParseException exception) {
            throw bad("effectiveFrom is a date like 2041-09-01.");
        }
        if (start.isBefore(today)) {
            throw bad("effectiveFrom cannot be before today (" + today + "); earlier dates keep the premiums they had.");
        }
        String reason = request.reason();
        StorableText.require(reason, "reason");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (reason != null && reason.length() > MAX_REASON) {
            throw bad("reason must be at most " + MAX_REASON + " characters.");
        }
        LaborPremiumRule row = new LaborPremiumRule();
        row.setRuleId(ids.generate());
        row.setProjectId(projectId);
        row.setUserId(userId);
        row.setFollowProjectYn(follow ? "Y" : "N");
        row.setEffectiveFrom(start);
        row.setOvertimeAfterMinutes(after);
        row.setOvertimePercent(overtime);
        row.setNightStart(nightStart);
        row.setNightEnd(nightEnd);
        row.setNightPercent(night);
        row.setHolidayPercent(request.holidayPercent());
        row.setWeeklyOvertimeAfterMinutes(weeklyAfter);
        row.setWeeklyOvertimePercent(weekly);
        row.setWeekStartsOn(weeklyAfter == null ? null : weekStart == null ? Integer.valueOf(1) : weekStart);
        row.setRestDaysAsHolidaysYn(restDays ? "Y" : "N");
        row.setHolidayOvertimeAfterMinutes(holidayAfter);
        row.setHolidayOvertimePercent(holidayOvertime);
        row.setReason(reason);
        row.setSetBy(access.requireCurrentUserId());
        row.setSetAt(OffsetDateTime.now());
        row.setRemovedYn("N");
        repository.saveAndFlush(row);
        return response(projectId, userId);
    }

    @Transactional
    public LaborPremiumRuleResponse removeRule(String projectId, String ruleId) {
        access.requireProjectOwnerAccess(projectId);
        return remove(projectId, null, ruleId);
    }

    @Transactional
    public LaborPremiumRuleResponse removeWorkerRule(String projectId, String userId, String ruleId) {
        access.requireProjectOwnerAccess(projectId);
        requireWorker(projectId, userId);
        return remove(projectId, userId, ruleId);
    }

    /** Removes the rule from a date that has not come yet, with every earlier save for that date; one that started stays. */
    private LaborPremiumRuleResponse remove(String projectId, String userId, String ruleId) {
        List<LaborPremiumRule> rows = rows(projectId, userId);
        LaborPremiumRule target = rows.stream().filter(row -> row.getRuleId().equals(ruleId) && !row.isRemoved()).findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!target.getEffectiveFrom().isAfter(calendars.today(projectId))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Premiums that have started stay; save new ones from a later date instead.");
        }
        String actor = access.requireCurrentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        for (LaborPremiumRule row : rows) {
            if (!row.isRemoved() && target.getEffectiveFrom().equals(row.getEffectiveFrom())) {
                row.setRemovedYn("Y");
                row.setRemovedBy(actor);
                row.setRemovedAt(now);
            }
        }
        repository.saveAllAndFlush(rows);
        return response(projectId, userId);
    }

    /** The rules in force for a worker, their own over the project's (decision 20), for working out pay; the caller checks access. */
    @Transactional(readOnly = true)
    public Rules inForce(String projectId, String userId) {
        return new Rules(inForceRows(rows(projectId, null)), inForceRows(rows(projectId, userId)));
    }

    /** The project's saves (no user), or one worker's own; newest first. */
    private List<LaborPremiumRule> rows(String projectId, String userId) {
        return userId == null ? repository.findAllByProjectIdAndUserIdIsNullOrderBySetAtDescRuleIdDesc(projectId)
            : repository.findAllByProjectIdAndUserIdOrderBySetAtDescRuleIdDesc(projectId, userId);
    }

    private static List<LaborPremiumRule> inForceRows(List<LaborPremiumRule> rows) {
        Map<LocalDate, String> counting = counting(rows);
        return rows.stream().filter(row -> counts(row, counting)).toList();
    }

    /** Of {@code rules}, the one that started last by {@code date}; empty before the first. */
    private static Optional<LaborPremiumRule> latest(List<LaborPremiumRule> rules, LocalDate date) {
        return rules.stream()
            .filter(rule -> !rule.getEffectiveFrom().isAfter(date))
            .max(Comparator.comparing(LaborPremiumRule::getEffectiveFrom));
    }

    private LaborPremiumRuleResponse response(String projectId, String userId) {
        List<LaborPremiumRule> rows = rows(projectId, userId);
        LocalDate today = calendars.today(projectId);
        Map<LocalDate, String> counting = counting(rows);
        LaborPremiumRule current = latest(inForceRows(rows), today).orElse(null);
        List<LaborPremiumRuleResponse.Line> lines = rows.stream()
            .map(row -> line(row, state(row, current, today, counting)))
            .toList();
        return new LaborPremiumRuleResponse(projectId, userId, today, current == null ? null : line(current, "current"), lines);
    }

    /** The save that counts for each start: the last one not removed (rows are newest first). */
    private static Map<LocalDate, String> counting(List<LaborPremiumRule> rows) {
        Map<LocalDate, String> counting = new HashMap<>();
        rows.stream().filter(row -> !row.isRemoved()).forEach(row -> counting.putIfAbsent(row.getEffectiveFrom(), row.getRuleId()));
        return counting;
    }

    private static boolean counts(LaborPremiumRule row, Map<LocalDate, String> counting) {
        return !row.isRemoved() && Objects.equals(counting.get(row.getEffectiveFrom()), row.getRuleId());
    }

    private static String state(LaborPremiumRule row, LaborPremiumRule current, LocalDate today, Map<LocalDate, String> counting) {
        if (row.isRemoved()) return "removed";
        if (!counts(row, counting)) return "replaced";
        if (current != null && current.getRuleId().equals(row.getRuleId())) return "current";
        return row.getEffectiveFrom().isAfter(today) ? "upcoming" : "past";
    }

    private static LaborPremiumRuleResponse.Line line(LaborPremiumRule row, String state) {
        return new LaborPremiumRuleResponse.Line(row.getRuleId(), row.getEffectiveFrom(), row.getOvertimeAfterMinutes(),
            row.getOvertimePercent(), clockText(row.getNightStart()), clockText(row.getNightEnd()), row.getNightPercent(),
            row.getHolidayPercent(), row.getWeeklyOvertimeAfterMinutes(), row.getWeeklyOvertimePercent(), row.getWeekStartsOn(),
            row.isRestDaysAsHolidays(), row.getHolidayOvertimeAfterMinutes(), row.getHolidayOvertimePercent(), row.isFollowProject(),
            row.getReason(), row.getSetBy(), row.getSetAt(), state, row.getRemovedBy(), row.getRemovedAt());
    }

    private void requireWorker(String projectId, String userId) {
        if (userId == null || !members.canRead(projectId, userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, userId + " is not a worker of this project.");
        }
    }

    private static String clockText(LocalTime time) {
        return time == null ? null : CLOCK.format(time);
    }

    private static LocalTime clock(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim(), CLOCK);
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a time like 22:00.");
        }
    }

    private static void percent(Integer value, String field) {
        if (value != null && (value < 1 || value > MAX_PERCENT)) {
            throw bad(field + " is a whole percent from 1 to " + MAX_PERCENT + ".");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
