package org.myweb.flowmat.domain.production.api.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * A worker's attendance beside the worked time recorded on runs, day by day (docs/domain/worker-calendar.md AT7).
 *
 * @param days every date of the range; {@code differenceMinutes} is attended less recorded (negative: more recorded on runs
 *             than attended)
 */
public record AttendanceComparisonResponse(String projectId, String userId, LocalDate from, LocalDate to, int attendedMinutes,
                                           int laborMinutes, List<Day> days) {

    public record Day(LocalDate date, int attendedMinutes, int laborMinutes, int differenceMinutes) {
    }
}
