package org.myweb.flowmat.domain.workforce.api.dto.request;

import java.math.BigDecimal;

/**
 * A new hourly rate (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 *
 * @param hourlyRate    per hour, at most 4 decimals; null is unknown
 * @param effectiveFrom the project date it applies from, today or later; empty for today
 * @param fromStart     true for a worker's first rate to apply from the start instead, which only fills unknown amounts
 */
public record WorkerLaborRateRequest(BigDecimal hourlyRate, String effectiveFrom, String reason, Boolean fromStart) {
}
