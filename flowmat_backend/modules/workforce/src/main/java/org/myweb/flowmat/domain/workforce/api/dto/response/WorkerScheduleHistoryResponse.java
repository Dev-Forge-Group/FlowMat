package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One save or removal of a worker's schedule version, with what it held then (docs/domain/worker-calendar.md WC63).
 *
 * @param effectiveFrom the version's date; null for the version from the start
 * @param action        saved or removed
 * @param rota          the rota then; null without one
 */
public record WorkerScheduleHistoryResponse(
    String historyId,
    String scheduleId,
    LocalDate effectiveFrom,
    String action,
    String reason,
    List<Shift> shifts,
    Rota rota,
    String savedBy,
    OffsetDateTime savedAt
) {
    public record Shift(String shiftStart, String shiftEnd, List<Integer> workDays, int rotaWeek) {
    }

    public record Rota(int weeks, LocalDate startsOn) {
    }
}
