package org.myweb.flowmat.domain.workforce.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A worker's attendance as pay by work day (docs/architecture/adr/ADR-028-pay-premiums.md), for labor cost. Callers require the
 * project owner.
 */
public interface WorkerPayQuery {

    /** The work days from {@code from} through {@code to} that have attendance, oldest first. */
    Map<LocalDate, PaidDay> paidDays(String projectId, String userId, LocalDate from, LocalDate to);

    /** @param paidMinutes attended minutes plus what the premiums of the day's rule add, 2 decimals */
    record PaidDay(int attendedMinutes, BigDecimal paidMinutes) {
    }

    /**
     * The worker's attended time on the work days {@code from} through {@code to}, in clock order and cut where the premiums of the
     * day's rule change (decision 24), each piece with what they add over the hourly rate there. Time outside attendance has none.
     */
    List<PremiumSpan> premiumSpans(String projectId, String userId, LocalDate from, LocalDate to);

    /** @param percent what the premiums add on [from, to), e.g. 100 for overtime at night at 50% each */
    record PremiumSpan(LocalDate workDate, Instant from, Instant to, int percent) {
    }
}
