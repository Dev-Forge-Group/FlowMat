package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One save or removal of a worker's own dates or day off, with what it held then (docs/domain/worker-calendar.md WC65).
 *
 * @param kind       day (a date's own shifts) or day_off
 * @param action     saved or removed
 * @param days       for a day change, each date with its shifts (none: no work that day) and reason; empty for a day off
 * @param dayOffId   the day off; null for a day change
 * @param reason     the reason given with the change; a day off's own reason
 * @param copiedFrom the worker the dates were copied from; null otherwise
 */
public record WorkerDayHistoryResponse(
    String historyId,
    String kind,
    String action,
    LocalDate firstDate,
    LocalDate lastDate,
    List<Day> days,
    String dayOffId,
    String reason,
    String copiedFrom,
    String savedBy,
    OffsetDateTime savedAt
) {
    public record Day(LocalDate date, List<Shift> shifts, String reason) {
    }

    public record Shift(String shiftStart, String shiftEnd) {
    }
}
