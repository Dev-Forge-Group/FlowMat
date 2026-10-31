package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.springframework.stereotype.Component;

/** Ends a run and its active steps in the transaction and under the run lock held by the caller. */
@Component
@RequiredArgsConstructor
class FlowRunTermination {
    private final FlowRunRepository flowRunRepository;
    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final FlowRunEventRecorder eventRecorder;
    private final FlowRunHttpInFlightRequests httpRequests;

    void stop(FlowRun run, String terminalStatus, String errorCode, String errorMessage, JsonNode payload, String actor) {
        String flowRunId = run.getFlowRunId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (FlowRunStep step : stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(flowRunId)) {
            if (!"planned".equals(step.getStatus()) && !"running".equals(step.getStatus())) {
                continue;
            }
            boolean wasRunning = "running".equals(step.getStatus());
            String stepStatus = "failed".equals(terminalStatus) && wasRunning ? "failed" : "cancelled";
            String stepErrorCode = "failed".equals(terminalStatus) && wasRunning
                ? errorCode : "RUN_" + terminalStatus.toUpperCase();
            if (wasRunning) {
                FlowRunStepAttempt attempt = attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.getStepId())
                    .orElseThrow(() -> new IllegalStateException("Running step has no attempt."));
                if (!"running".equals(attempt.getStatus())) {
                    throw new IllegalStateException("Running step's latest attempt is not running.");
                }
                attempt.setStatus(stepStatus);
                attempt.setEndedAt(now);
                attempt.setErrorCode(stepErrorCode);
                attempt.setErrorMessage(errorMessage);
                attemptRepository.save(attempt);
            }
            step.setStatus(stepStatus);
            step.setEndedAt(now);
            step.setErrorCode(stepErrorCode);
            step.setErrorMessage(errorMessage);
            stepRepository.save(step);
            eventRecorder.record(flowRunId, step.getStepId(), "step_" + stepStatus, payload, actor);
        }
        run.setStatus(terminalStatus);
        run.setEndedAt(now);
        flowRunRepository.save(run);
        eventRecorder.record(flowRunId, null, "run_" + terminalStatus, payload, actor);
        httpRequests.cancelAfterCommit(flowRunId);
    }
}
