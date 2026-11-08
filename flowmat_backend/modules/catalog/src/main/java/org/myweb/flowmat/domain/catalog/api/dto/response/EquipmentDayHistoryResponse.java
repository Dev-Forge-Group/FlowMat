package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One save or removal of an equipment's own dates or downtime, with what it held then (docs/domain/equipment-schedule.md
 * CV12).
 *
 * @param kind       day (a date's own shifts) or downtime
 * @param action     saved or removed
 * @param firstDate  a day change's first date; null for a downtime
 * @param days       for a day change, each date with its shifts (none: closed that day) and reason; empty for a downtime
 * @param reason     the reason given with the change; a downtime's own reason
 * @param copiedFrom the equipment the dates were copied from; null otherwise
 */
public record EquipmentDayHistoryResponse(
    String historyId,
    String kind,
    String action,
    LocalDate firstDate,
    LocalDate lastDate,
    List<Day> days,
    String downtimeId,
    String downtimeType,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
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
