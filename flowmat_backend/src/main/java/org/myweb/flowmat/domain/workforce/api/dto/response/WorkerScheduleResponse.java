package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One version of a worker's weekly shifts and rota (docs/architecture/adr/ADR-013-rota-history.md): it applies from
 * {@code effectiveFrom} (null: from the start) until the next version starts; {@code current} is the one that applies today.
 * {@code weeklyHours} is worked out as in the worker's calendar (WC47). {@code reason}, {@code updatedBy} and {@code updatedAt}
 * say why and by whom it was last saved (docs/architecture/adr/ADR-017-version-reasons.md).
 */
public record WorkerScheduleResponse(
    String scheduleId,
    LocalDate effectiveFrom,
    boolean current,
    boolean calendarSet,
    BigDecimal weeklyHours,
    List<WorkerCalendarResponse.Shift> shifts,
    WorkerCalendarResponse.Rota rota,
    String reason,
    String updatedBy,
    OffsetDateTime updatedAt
) {
}
