package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * When a production-linked run's step can start once a connection delay is over
 * (docs/architecture/adr/ADR-015-shift-aware-delay.md): flow runs ask, and production answers from the work order's
 * equipment and crew (docs/architecture/adr/ADR-016-crew-aware-delay.md), so flowrun does not depend on production
 * (docs/architecture/adr/ADR-002-module-dependency.md).
 */
public interface LinkedRunWorkingTime {

    /**
     * The working time to start at instead of {@code readyAt}: the next time the production run's work order equipment is
     * available and its full-share crew with shifts all work, when {@code readyAt} falls outside it. Empty to keep
     * {@code readyAt}: no work order, neither equipment nor such crew, nothing together within a year, or {@code readyAt}
     * already in working time.
     */
    Optional<WorkingStart> workingStart(String productionRunId, OffsetDateTime readyAt);

    /**
     * When {@code working} of the same working time has passed since {@code from}, for a delay that counts only it
     * (docs/architecture/adr/ADR-022-working-time-delay.md). Empty to count all time instead: no work order, neither
     * equipment nor such crew, or not that much working time within a year.
     */
    Optional<WorkingStart> afterWorking(String productionRunId, OffsetDateTime from, Duration working);

    /** The start, the work order's equipment (null without one) and the crew whose working time it followed. */
    record WorkingStart(OffsetDateTime at, String equipmentId, List<String> crew) {
    }
}
