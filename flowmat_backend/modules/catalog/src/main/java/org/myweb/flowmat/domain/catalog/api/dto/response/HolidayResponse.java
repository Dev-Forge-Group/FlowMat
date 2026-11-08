package org.myweb.flowmat.domain.catalog.api.dto.response;

import java.time.LocalDate;

/** A date on which no calendar shift starts (docs/domain/equipment-schedule.md "휴일"). */
public record HolidayResponse(String holidayId, LocalDate date, String name, String createdBy) {
}
