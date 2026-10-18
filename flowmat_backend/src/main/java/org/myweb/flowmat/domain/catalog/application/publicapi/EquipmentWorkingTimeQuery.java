package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * When a piece of equipment can next work, for other bounded contexts (docs/architecture/adr/ADR-015-shift-aware-delay.md),
 * which use this instead of the catalog repositories (docs/architecture/adr/ADR-002-module-dependency.md). No access check;
 * callers check it.
 */
public interface EquipmentWorkingTimeQuery {

    /**
     * The first moment at or after {@code at} in the equipment's available time: its shifts less holidays and downtime, each
     * date with its own calendar version. {@code at} itself when that date has no calendar, so the equipment runs around
     * the clock. Empty when the equipment is gone or the 366 days from {@code at} hold no available time.
     */
    Optional<OffsetDateTime> nextWorkingTime(String equipmentId, OffsetDateTime at);
}
