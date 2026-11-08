package org.myweb.flowmat.domain.workforce.api.dto.request;

/**
 * Copies a worker's own dates to another worker of the project (docs/domain/worker-calendar.md WC41). Dates are
 * YYYY-MM-DD; without {@code fromDate} or {@code throughDate} the range is open on that side.
 */
public record WorkerDayCopyRequest(String toUserId, String fromDate, String throughDate) {
}
