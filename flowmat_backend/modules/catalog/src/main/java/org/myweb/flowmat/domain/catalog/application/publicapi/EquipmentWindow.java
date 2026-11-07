package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.math.BigDecimal;

/**
 * A piece of equipment's working time in a window (docs/domain/equipment-schedule.md): whether it has a calendar, the
 * hours its shifts leave less downtime, and the downtime inside its shifts. Without a calendar all the window is working time.
 */
public record EquipmentWindow(boolean calendarSet, BigDecimal availableHours, BigDecimal downtimeHours) {
}
