package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.math.BigDecimal;

/** A worker's shift hours in a window (WC8); {@code calendarSet} false when they have no shifts. */
public record WorkerHours(boolean calendarSet, BigDecimal hours) {
}
