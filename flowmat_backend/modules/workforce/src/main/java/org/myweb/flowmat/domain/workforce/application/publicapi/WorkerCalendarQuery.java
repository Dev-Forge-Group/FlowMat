package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Workers' time for other bounded contexts (docs/domain/worker-calendar.md WC8), which use this instead of the workforce
 * repositories (docs/architecture/adr/ADR-002-module-dependency.md). Callers enforce project access.
 */
public interface WorkerCalendarQuery {

    /**
     * Each worker's shift time in [from, to), less their days off and the project's holidays. A worker without shifts is
     * {@code calendarSet = false} with 0 hours: their time is not known, not free.
     */
    Map<String, WorkerHours> workingHours(String projectId, Collection<String> userIds, OffsetDateTime from, OffsetDateTime to);

    /**
     * The same time as {@link #workingHours} as merged spans (WC17), so callers can count the part inside another window, such
     * as a work order's.
     */
    Map<String, WorkerTime> workingTime(String projectId, Collection<String> userIds, OffsetDateTime from, OffsetDateTime to);
}
