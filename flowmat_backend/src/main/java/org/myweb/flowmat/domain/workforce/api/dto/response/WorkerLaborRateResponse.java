package org.myweb.flowmat.domain.workforce.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A worker's hourly rates in a project, for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 *
 * @param today   the project's date
 * @param current the rate in effect today; null when there is none
 * @param lines   every save, newest first, removed ones included
 */
public record WorkerLaborRateResponse(String projectId, String userId, LocalDate today, Line current, List<Line> lines) {

    /**
     * @param hourlyRate    null is unknown
     * @param effectiveFrom null from the start
     * @param state         current, upcoming, past (a later start took over), replaced (saved again for the same start) or
     *                      removed
     */
    public record Line(String rateId, BigDecimal hourlyRate, LocalDate effectiveFrom, String reason, String setBy,
                       OffsetDateTime setAt, String state, String removedBy, OffsetDateTime removedAt) {
    }
}
