package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A worker's attendance as pay over a period (docs/architecture/adr/ADR-028-pay-premiums.md), for the project owner only.
 *
 * @param days         work days with attended minutes, oldest first
 * @param amount       the known amounts added up, 4 decimals
 * @param costComplete false when a day with attendance has no rate
 */
public record WorkerAttendancePayResponse(String projectId, String userId, LocalDate from, LocalDate to, List<Day> days,
                                          int attendedMinutes, BigDecimal premiumMinutes, BigDecimal paidMinutes, BigDecimal amount,
                                          boolean costComplete) {

    /**
     * One work day (the date of the clock-in).
     *
     * @param overtimeMinutes attended minutes past the overtime start of the day's rule; 0 without one
     * @param weeklyOvertimeMinutes regular minutes of the day past the weekly overtime start of its week (decision 14); 0 without
     * @param holidayOvertimeMinutes on a holiday, minutes past the holiday overtime start, then overtimeMinutes is 0 (decision 18)
     * @param nightMinutes    attended minutes in the day's night window; 0 without one
     * @param holiday         the day is a project holiday
     * @param restDayHoliday  the day had none of the worker's scheduled time and its rule pays that like a holiday (decision 16)
     * @param premiumMinutes  the minutes the premiums add: each premium's minutes times its percent, 2 decimals
     * @param paidMinutes     attended minutes plus premiumMinutes
     * @param ruleId          the rule of the day; null before the first
     * @param amount          paidMinutes at the day's hourly rate, 4 decimals; null without a rate
     */
    public record Day(LocalDate workDate, int attendedMinutes, int overtimeMinutes, int weeklyOvertimeMinutes,
                      int holidayOvertimeMinutes, int nightMinutes, boolean holiday, boolean restDayHoliday,
                      BigDecimal premiumMinutes, BigDecimal paidMinutes, String ruleId, BigDecimal hourlyRate, BigDecimal amount) {
    }
}
