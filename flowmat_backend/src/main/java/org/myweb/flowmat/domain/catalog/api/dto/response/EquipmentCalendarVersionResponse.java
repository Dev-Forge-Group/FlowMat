package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One version of a piece of equipment's calendar (docs/architecture/adr/ADR-014-equipment-calendar-history.md): it applies
 * from {@code effectiveFrom} (null: from the start) until the next; {@code current} is today's, and {@code alwaysRunning}
 * means it has no shifts, so the equipment runs around the clock. {@code reason}, {@code updatedBy} and {@code updatedAt} say why
 * and by whom it was last saved (docs/architecture/adr/ADR-017-version-reasons.md).
 */
public record EquipmentCalendarVersionResponse(
    String scheduleId,
    LocalDate effectiveFrom,
    boolean current,
    boolean alwaysRunning,
    List<EquipmentScheduleResponse.Shift> shifts,
    BigDecimal weeklyHours,
    String reason,
    String updatedBy,
    OffsetDateTime updatedAt
) {
}
