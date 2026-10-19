package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * When a piece of equipment can next work, for other bounded contexts (docs/architecture/adr/ADR-015-shift-aware-delay.md),
 * which use this instead of the catalog repositories (docs/architecture/adr/ADR-002-module-dependency.md). No access check;
 * callers check it.
 */
public interface EquipmentWorkingTimeQuery {

    /**
     * When a delayed step may start on the equipment in [from, to), earliest first and none overlapping: on dates with a
     * calendar its available time (shifts less holidays and downtime, each date with its own calendar version), and the whole
     * of each date without one, which runs around the clock, downtime or not (docs/architecture/adr/ADR-015-shift-aware-delay.md
     * decisions 2 and 4). Empty when the equipment is gone. The caller keeps the window at most 366 days long.
     */
    Optional<List<EquipmentSlot>> workingStretches(String equipmentId, OffsetDateTime from, OffsetDateTime to);
}
