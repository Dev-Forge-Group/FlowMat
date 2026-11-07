package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Working time in [from, to): the shifts that fall in the window (the whole window without a calendar), less the downtime
 * inside them. {@code capacity} is the available hours times the capacity per hour, or null without one.
 */
public record EquipmentAvailabilityResponse(
    String equipmentId,
    OffsetDateTime from,
    OffsetDateTime to,
    boolean calendarSet,
    BigDecimal workingHours,
    BigDecimal downtimeHours,
    BigDecimal availableHours,
    BigDecimal capacityPerHour,
    BigDecimal capacity,
    /** Project holidays whose shift would have fallen in the window; their hours are not working hours. */
    List<LocalDate> holidays
) {
}
