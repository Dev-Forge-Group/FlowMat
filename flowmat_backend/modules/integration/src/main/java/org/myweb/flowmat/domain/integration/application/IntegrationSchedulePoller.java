package org.myweb.flowmat.domain.integration.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for scheduled integration runs whose time has come (docs/architecture/adr/ADR-038-integration-jobs.md
 * decision 6). Every server polls; the row locks decide which one takes a run. Tests switch it off and call
 * {@link IntegrationRunService#runDue()} themselves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.integration.schedule", name = "poll-enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationSchedulePoller {

    private final IntegrationRunService runs;

    @Scheduled(
        fixedDelayString = "${app.integration.schedule.poll-interval:PT1M}",
        initialDelayString = "${app.integration.schedule.poll-interval:PT1M}"
    )
    public void poll() {
        try {
            int done = runs.runDue();
            if (done > 0) log.info("Integration schedule poll ran {} jobs", done);
        } catch (RuntimeException exception) {
            log.warn("Integration schedule poll failed", exception);
        }
    }
}
