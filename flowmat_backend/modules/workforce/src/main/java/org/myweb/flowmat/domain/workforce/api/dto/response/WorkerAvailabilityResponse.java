package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A worker's time in a window (docs/domain/worker-calendar.md WC6).
 *
 * @param daysOff  the worker's days off in the window
 * @param holidays the project's holidays in the window
 */
public record WorkerAvailabilityResponse(
    String userId,
    OffsetDateTime from,
    OffsetDateTime to,
    boolean calendarSet,
    BigDecimal workingHours,
    List<LocalDate> daysOff,
    List<LocalDate> holidays
) {
}
