package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One save or removal of an equipment calendar version, with what it held then (docs/domain/equipment-schedule.md CV10).
 *
 * @param effectiveFrom the version's date; null for the version from the start
 * @param action        saved or removed
 * @param shifts        the shifts then; none means around the clock
 */
public record EquipmentCalendarHistoryResponse(
    String historyId,
    String scheduleId,
    LocalDate effectiveFrom,
    String action,
    String reason,
    List<Shift> shifts,
    String savedBy,
    OffsetDateTime savedAt
) {
    public record Shift(String shiftStart, String shiftEnd, List<Integer> workDays) {
    }
}
