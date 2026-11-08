package org.myweb.flowmat.domain.workforce.api.dto.request;

import java.util.UUID;

/**
 * A stretch at work (docs/domain/worker-calendar.md AT1).
 *
 * @param clockIn  a date and time with an offset
 * @param clockOut empty while still at work
 */
public record WorkerAttendanceRequest(UUID requestId, String clockIn, String clockOut, String note) {
}
