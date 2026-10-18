package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * When a production-linked run's step can start once a connection delay is over
 * (docs/architecture/adr/ADR-015-shift-aware-delay.md): flow runs ask, and production answers from the work order's
 * equipment, so flowrun does not depend on production (docs/architecture/adr/ADR-002-module-dependency.md).
 */
public interface LinkedRunWorkingTime {

    /**
     * The working time to start at instead of {@code readyAt}: the next available time of the production run's work order
     * equipment when {@code readyAt} falls outside it. Empty to keep {@code readyAt}: no work order or equipment, no
     * calendar on that date, nothing available within a year, or {@code readyAt} already in working time.
     */
    Optional<WorkingStart> workingStart(String productionRunId, OffsetDateTime readyAt);

    /** The start and the equipment whose available time set it. */
    record WorkingStart(OffsetDateTime at, String equipmentId) {
    }
}
