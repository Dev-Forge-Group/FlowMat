package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class FlowRunStepAttemptLifecycle {
    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final FlowRunEventRecorder eventRecorder;

    /** {@code timeoutSeconds}: the node's time limit in a graph run (EP7); null for none. */
    void beginAttempt(FlowRunStep step, String eventType, Integer timeoutSeconds, String actorId) {
        int nextNumber = attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.getStepId())
            .map(previous -> previous.getAttemptNo() + 1).orElse(1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        FlowRunStepAttempt attempt = new FlowRunStepAttempt();
        attempt.setAttemptId(idGenerator.generate());
        attempt.setStepId(step.getStepId());
        attempt.setAttemptNo(nextNumber);
        attempt.setStatus("running");
        attempt.setStartedAt(now);
        attempt.setTimeoutAt(timeoutSeconds == null ? null : now.plusSeconds(timeoutSeconds));
        attemptRepository.save(attempt);
        step.setStatus("running");
        if (step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        step.setEndedAt(null);
        step.setErrorCode(null);
        step.setErrorMessage(null);
        stepRepository.save(step);
        eventRecorder.record(step.getFlowRunId(), step.getStepId(), eventType,
            objectMapper.createObjectNode().put("attemptNo", nextNumber), actorId);
    }

    FlowRunStepAttempt runningAttempt(FlowRunStep step) {
        if (!"running".equals(step.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a running step can end.");
        }
        FlowRunStepAttempt attempt = attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.getStepId())
            .orElseThrow(() -> new IllegalStateException("Running step has no attempt."));
        if (!"running".equals(attempt.getStatus())) {
            throw new IllegalStateException("Running step's latest attempt is not running.");
        }
        return attempt;
    }

    /** A report naming an attempt that is no longer running is late, after a timeout or retry (EP8). */
    FlowRunStepAttempt runningAttempt(FlowRunStep step, Integer attemptNo) {
        FlowRunStepAttempt attempt = runningAttempt(step);
        if (attemptNo != null && attemptNo.intValue() != attempt.getAttemptNo()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Attempt " + attemptNo + " is no longer running.");
        }
        return attempt;
    }
}
