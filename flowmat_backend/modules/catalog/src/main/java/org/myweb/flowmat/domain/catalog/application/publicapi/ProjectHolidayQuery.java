package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.LocalDate;
import java.util.Set;

/**
 * The project's holidays for other bounded contexts (docs/domain/worker-calendar.md WC4), which use this instead of the
 * catalog repositories (docs/architecture/adr/ADR-002-module-dependency.md). Callers enforce project access.
 */
public interface ProjectHolidayQuery {

    /** The project's holidays from {@code first} to {@code last}, both included. */
    Set<LocalDate> holidays(String projectId, LocalDate first, LocalDate last);
}
