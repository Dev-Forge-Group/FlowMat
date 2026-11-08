package org.myweb.flowmat.domain.catalog.api.dto.request;

/** Takes time out of the equipment's calendar. Times are ISO date-times with an offset; the type defaults to maintenance. */
public record EquipmentDowntimeRequest(
    String downtimeType,
    String startsAt,
    String endsAt,
    String reason
) {
}
