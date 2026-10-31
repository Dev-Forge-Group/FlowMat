package org.myweb.flowmat.domain.workforce.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.myweb.flowmat.global.util.WholeNumberDeserializer;

/**
 * The project's pay premiums from a date (docs/architecture/adr/ADR-028-pay-premiums.md). Each premium is optional; send none to
 * stop them from that date.
 *
 * @param effectiveFrom        the project date they apply from, today or later; empty for today
 * @param overtimeAfterMinutes minutes of a work day (1 to 1440) after which overtimePercent is added, both or neither
 * @param nightStart           "22:00" in project time, with nightEnd ("06:00" runs past midnight) and nightPercent, all or none
 * @param holidayPercent       added to every minute of a project holiday
 * @param weeklyOvertimeAfterMinutes regular minutes of a week (1 to 10080) after which weeklyOvertimePercent is added, both or
 *                                   neither; weekStartsOn (1 Monday to 7 Sunday, Monday when empty) goes with them
 * @param restDaysAsHolidays   true to pay work on a day without any of the worker's scheduled time with holidayPercent too
 * @param holidayOvertimeAfterMinutes minutes of a holiday (1 to 1440) after which holidayOvertimePercent is added on top of
 *                                    holidayPercent in place of the daily overtime, both or neither, only with holidayPercent
 * @param followProject        for a worker's own premiums only: from effectiveFrom the worker follows the project's again; no premiums
 */
public record LaborPremiumRuleRequest(
    String effectiveFrom,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer overtimeAfterMinutes,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer overtimePercent,
    String nightStart,
    String nightEnd,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer nightPercent,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer holidayPercent,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer weeklyOvertimeAfterMinutes,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer weeklyOvertimePercent,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer weekStartsOn,
    Boolean restDaysAsHolidays,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer holidayOvertimeAfterMinutes,
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer holidayOvertimePercent,
    Boolean followProject,
    String reason
) {
}
