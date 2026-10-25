package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A worker's attendance over a range of project dates (docs/domain/worker-calendar.md AT5).
 *
 * @param open  the stretch still clocked in, whenever it began; null when not at work
 * @param days  every date of the range with the minutes of its closed stretches that count
 * @param lines the stretches that began in the range, cancelled ones included, oldest first
 */
public record WorkerAttendanceResponse(String projectId, String userId, LocalDate from, LocalDate to, LocalDate today, Line open,
                                       List<Day> days, List<Line> lines) {

    public record Day(LocalDate date, int attendedMinutes) {
    }

    /** @param minutes whole minutes from clock-in to clock-out; null while open */
    public record Line(String attendanceId, LocalDate workDate, OffsetDateTime clockIn, OffsetDateTime clockOut, Integer minutes,
                       String note, String recordedBy, OffsetDateTime recordedAt, boolean cancelled, String cancelledBy,
                       OffsetDateTime cancelledAt, String cancelReason) {
    }
}
