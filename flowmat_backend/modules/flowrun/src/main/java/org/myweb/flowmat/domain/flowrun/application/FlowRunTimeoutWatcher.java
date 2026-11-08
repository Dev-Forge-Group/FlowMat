package org.myweb.flowmat.domain.flowrun.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails running graph attempts past their node's time limit (docs/domain/flow-run-execution-policy.md EP7). Each attempt
 * is checked again in its own transaction under its run's lock, so overlapping sweeps record it once, and one failing
 * attempt does not stop the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowRunTimeoutWatcher {

    private static final int BATCH = 100;

    private final FlowRunStepAttemptRepository attempts;
    private final FlowRunStepService steps;

    @Scheduled(
        fixedDelayString = "${app.flow-run.timeout-sweep-interval:PT15S}",
        initialDelayString = "${app.flow-run.timeout-sweep-interval:PT15S}"
    )
    public void sweep() {
        List<String> due = attempts.findTimedOutAttemptIds(OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, BATCH));
        int failed = 0;
        for (String attemptId : due) {
            try {
                steps.timeOut(attemptId);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Flow run timeout check failed for attempt {}", attemptId, e);
            }
        }
        if (failed > 0) {
            log.warn("Flow run timeout sweep: {} of {} attempts failed", failed, due.size());
        }
    }
}
