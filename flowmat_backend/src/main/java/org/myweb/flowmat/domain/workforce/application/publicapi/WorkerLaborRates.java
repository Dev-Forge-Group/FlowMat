package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workers' hourly rates in one project, each with the project date it applies from (docs/architecture/adr/ADR-025-worker-labor-cost.md).
 * Removed rates are left out.
 */
public record WorkerLaborRates(Map<String, List<Rate>> byWorker) {
    private static final Comparator<Rate> EARLIEST_FIRST = Comparator
        .comparing(Rate::effectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(Rate::setAt)
        .thenComparing(Rate::rateId);

    /** @param effectiveFrom null from the start; @param hourlyRate null when unknown */
    public record Rate(String rateId, LocalDate effectiveFrom, BigDecimal hourlyRate, OffsetDateTime setAt) {
    }

    /**
     * The rate on {@code date}: of the rates that started by then, the one that started last, and of those starting the same
     * day the one saved last. Empty when there is none or it is unknown.
     */
    public Optional<BigDecimal> on(String userId, LocalDate date) {
        Rate found = null;
        for (Rate rate : byWorker.getOrDefault(userId, List.of()).stream().sorted(EARLIEST_FIRST).toList()) {
            if (rate.effectiveFrom() == null || !rate.effectiveFrom().isAfter(date)) {
                found = rate;
            }
        }
        return found == null ? Optional.empty() : Optional.ofNullable(found.hourlyRate());
    }
}
