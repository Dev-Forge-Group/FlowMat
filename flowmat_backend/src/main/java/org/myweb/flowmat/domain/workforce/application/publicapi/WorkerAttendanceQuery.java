package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.time.LocalDate;
import java.util.Map;

/** Attendance for comparisons (docs/architecture/adr/ADR-026-attendance.md). Callers check who may see it. */
public interface WorkerAttendanceQuery {
    /** Minutes clocked in and out on each project date from {@code from} through {@code to}, cancelled and open lines left out. */
    Map<LocalDate, Integer> attendedMinutes(String projectId, String userId, LocalDate from, LocalDate to);
}
