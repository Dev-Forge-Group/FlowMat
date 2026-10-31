package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The project's pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md): the rule of today and every save, newest first.
 *
 * @param userId  the worker whose own premiums these are (decision 20); null for the project's
 * @param current null before the first rule starts
 */
public record LaborPremiumRuleResponse(String projectId, String userId, LocalDate today, Line current, List<Line> lines) {

    /**
     * One save; an empty premium is not paid.
     *
     * @param nightStart   "22:00" in project time
     * @param weekStartsOn 1 Monday to 7 Sunday; null without weekly overtime
     * @param state      current, upcoming, past (a later start took over), replaced (saved again for the same start) or removed
     */
    public record Line(String ruleId, LocalDate effectiveFrom, Integer overtimeAfterMinutes, Integer overtimePercent, String nightStart,
                       String nightEnd, Integer nightPercent, Integer holidayPercent, Integer weeklyOvertimeAfterMinutes,
                       Integer weeklyOvertimePercent, Integer weekStartsOn, boolean restDaysAsHolidays,
                       Integer holidayOvertimeAfterMinutes, Integer holidayOvertimePercent, boolean followProject, String reason, String setBy,
                       OffsetDateTime setAt,
                       String state, String removedBy, OffsetDateTime removedAt) {
    }
}
