package org.myweb.flowmat.domain.catalog.api.dto.request;

/**
 * Copies an equipment's own dates to another equipment of the project (docs/domain/equipment-schedule.md D7). Dates are
 * YYYY-MM-DD; without {@code fromDate} or {@code throughDate} the range is open on that side.
 */
public record EquipmentDayCopyRequest(String toEquipmentId, String fromDate, String throughDate) {
}
