package org.myweb.flowmat.domain.integration.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.scheduling.support.CronExpression;

/**
 * A job's schedule (docs/architecture/adr/ADR-038-integration-jobs.md decision 6): five cron fields (minute, hour,
 * day of month, month, day of week) read in the project's time zone, never closer than 15 minutes apart.
 */
final class IntegrationSchedule {

    static final Duration SHORTEST = Duration.ofMinutes(15);
    /** How far ahead the gaps are checked: every weekday and hour comes up at least once. */
    private static final Duration CHECKED = Duration.ofDays(8);

    private final CronExpression cron;

    private IntegrationSchedule(CronExpression cron) {
        this.cron = cron;
    }

    /** The schedule, or a 400 that says what is wrong with it. */
    static IntegrationSchedule parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) throw bad("Write the schedule as five cron fields, such as 0 6 * * 1-5.");
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw bad("A schedule has five fields (minute, hour, day of month, month, day of week); this one has " + fields.length + ".");
        }
        try {
            return new IntegrationSchedule(CronExpression.parse("0 " + String.join(" ", fields)));
        } catch (IllegalArgumentException exception) {
            throw bad("The schedule cannot be read: " + exception.getMessage());
        }
    }

    /** The first time after {@code after}, or null when the schedule never comes again. */
    OffsetDateTime next(ZoneId zone, OffsetDateTime after) {
        ZonedDateTime next = cron.next(after.atZoneSameInstant(zone));
        return next == null ? null : next.toOffsetDateTime();
    }

    /** Refuses a schedule that never comes or comes back sooner than 15 minutes. */
    void requireUsable(ZoneId zone, OffsetDateTime from) {
        OffsetDateTime first = next(zone, from);
        if (first == null) throw bad("This schedule never comes.");
        OffsetDateTime until = from.plus(CHECKED);
        OffsetDateTime previous = first;
        while (previous.isBefore(until)) {
            OffsetDateTime following = next(zone, previous);
            if (following == null) return;
            Duration gap = Duration.between(previous, following);
            if (gap.compareTo(SHORTEST) < 0) {
                throw bad("Runs must be at least 15 minutes apart; this schedule has runs " + gap.toMinutes() + " minutes apart.");
            }
            previous = following;
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
