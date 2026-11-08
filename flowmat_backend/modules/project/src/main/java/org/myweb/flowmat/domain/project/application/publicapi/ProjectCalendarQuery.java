package org.myweb.flowmat.domain.project.application.publicapi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
/** Business dates use the project's calendar; timestamps remain instants. Callers enforce project access. */
public interface ProjectCalendarQuery {
    ZoneId zone(String projectId);
    LocalDate today(String projectId);
    LocalDate date(String projectId, Instant instant);
}
