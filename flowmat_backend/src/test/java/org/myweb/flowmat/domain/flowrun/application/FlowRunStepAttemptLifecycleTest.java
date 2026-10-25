package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class FlowRunStepAttemptLifecycleTest {

    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunStepAttemptRepository attemptRepository;
    @Mock private IdGenerator idGenerator;
    @Mock private FlowRunEventRecorder eventRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowRunStepAttemptLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new FlowRunStepAttemptLifecycle(stepRepository, attemptRepository, idGenerator,
            objectMapper, eventRecorder);
    }

    @Test
    void firstAttemptSharesTheUtcStartTimeAndClearsOnlyTheStepsEndAndErrors() {
        FlowRunStep step = step("planned");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("attempt-1");
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        lifecycle.beginAttempt(step, "step_started", null, "user-1");

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
        ArgumentCaptor<FlowRunStepAttempt> saved = ArgumentCaptor.forClass(FlowRunStepAttempt.class);
        InOrder writes = inOrder(attemptRepository, idGenerator, stepRepository, eventRecorder);
        writes.verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("step-1");
        writes.verify(idGenerator).generate();
        writes.verify(attemptRepository).save(saved.capture());
        writes.verify(stepRepository).save(step);
        writes.verify(eventRecorder).record("run-1", "step-1", "step_started",
            objectMapper.createObjectNode().put("attemptNo", 1), "user-1");
        writes.verifyNoMoreInteractions();

        FlowRunStepAttempt attempt = saved.getValue();
        assertEquals("attempt-1", attempt.getAttemptId());
        assertEquals("step-1", attempt.getStepId());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals("running", attempt.getStatus());
        assertEquals(ZoneOffset.UTC, attempt.getStartedAt().getOffset());
        assertFalse(attempt.getStartedAt().isBefore(before));
        assertFalse(attempt.getStartedAt().isAfter(after));
        assertEquals(attempt.getStartedAt(), step.getStartedAt());
        assertNull(attempt.getTimeoutAt());
        assertNull(attempt.getEndedAt());
        assertNull(attempt.getRetryAt());
        assertNull(attempt.getOverdueAt());
        assertNull(attempt.getErrorCode());
        assertNull(attempt.getErrorMessage());
        assertEquals("running", step.getStatus());
        assertNull(step.getEndedAt());
        assertNull(step.getErrorCode());
        assertNull(step.getErrorMessage());
        assertStepMetadataPreserved(step);
    }

    @Test
    void retryUsesTheLatestNumberAndItsOwnTimeoutWhileKeepingTheOriginalStepStartAndPreviousAttempt() {
        FlowRunStep step = step("failed");
        OffsetDateTime originalStart = OffsetDateTime.parse("2026-10-01T09:00:00+09:00");
        step.setStartedAt(originalStart);
        FlowRunStepAttempt previous = attempt(256, "failed");
        previous.setEndedAt(OffsetDateTime.parse("2026-10-01T00:05:00Z"));
        previous.setRetryAt(OffsetDateTime.parse("2026-10-01T00:06:00Z"));
        previous.setErrorCode("PREVIOUS_FAILURE");
        previous.setErrorMessage("Previous attempt failed.");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(previous));
        when(idGenerator.generate()).thenReturn("attempt-257");
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        lifecycle.beginAttempt(step, "step_retried", 75, null);

        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);
        ArgumentCaptor<FlowRunStepAttempt> saved = ArgumentCaptor.forClass(FlowRunStepAttempt.class);
        verify(attemptRepository).save(saved.capture());
        FlowRunStepAttempt next = saved.getValue();
        assertEquals("attempt-257", next.getAttemptId());
        assertEquals("step-1", next.getStepId());
        assertEquals(257, next.getAttemptNo());
        assertEquals("running", next.getStatus());
        assertEquals(ZoneOffset.UTC, next.getStartedAt().getOffset());
        assertFalse(next.getStartedAt().isBefore(before));
        assertFalse(next.getStartedAt().isAfter(after));
        assertEquals(next.getStartedAt().plusSeconds(75), next.getTimeoutAt());
        assertSame(originalStart, step.getStartedAt());
        assertEquals("running", step.getStatus());
        assertNull(step.getEndedAt());
        assertNull(step.getErrorCode());
        assertNull(step.getErrorMessage());
        assertStepMetadataPreserved(step);
        assertEquals("failed", previous.getStatus());
        assertEquals(256, previous.getAttemptNo());
        assertEquals(OffsetDateTime.parse("2026-10-01T00:05:00Z"), previous.getEndedAt());
        assertEquals(OffsetDateTime.parse("2026-10-01T00:06:00Z"), previous.getRetryAt());
        assertEquals("PREVIOUS_FAILURE", previous.getErrorCode());
        assertEquals("Previous attempt failed.", previous.getErrorMessage());
        verify(stepRepository).save(step);
        verify(eventRecorder).record("run-1", "step-1", "step_retried",
            objectMapper.createObjectNode().put("attemptNo", 257), null);
    }

    @Test
    void beginAttemptForwardsTheActorWithoutNormalization() {
        FlowRunStep step = step("planned");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("attempt-1");

        lifecycle.beginAttempt(step, "step_started", null, "  actor  ");

        verify(eventRecorder).record("run-1", "step-1", "step_started",
            objectMapper.createObjectNode().put("attemptNo", 1), "  actor  ");
    }

    @Test
    void aFailedAttemptSavePropagatesBeforeChangingTheStepOrRecordingAnEvent() {
        FlowRunStep step = step("failed");
        OffsetDateTime endedAt = step.getEndedAt();
        IllegalStateException failure = new IllegalStateException("Attempt could not be saved.");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("attempt-1");
        when(attemptRepository.save(any(FlowRunStepAttempt.class))).thenThrow(failure);

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> lifecycle.beginAttempt(step, "step_retried", null, null));

        assertSame(failure, rejected);
        assertEquals("failed", step.getStatus());
        assertNull(step.getStartedAt());
        assertSame(endedAt, step.getEndedAt());
        assertEquals("OLD_ERROR", step.getErrorCode());
        assertEquals("Old failure.", step.getErrorMessage());
        verifyNoInteractions(stepRepository, eventRecorder);
    }

    @Test
    void aFailedStepSavePropagatesBeforeRecordingAnEvent() {
        FlowRunStep step = step("planned");
        IllegalStateException failure = new IllegalStateException("Step could not be saved.");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("attempt-1");
        when(stepRepository.save(step)).thenThrow(failure);

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> lifecycle.beginAttempt(step, "step_started", null, "user-1"));

        assertSame(failure, rejected);
        verify(attemptRepository).save(any(FlowRunStepAttempt.class));
        verifyNoInteractions(eventRecorder);
    }

    @Test
    void runningAttemptReturnsTheSameLatestEntityWithoutWriting() {
        FlowRunStep step = step("running");
        FlowRunStepAttempt latest = attempt(256, "running");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(latest));

        assertSame(latest, lifecycle.runningAttempt(step));

        verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("step-1");
        verifyNoMoreInteractions(attemptRepository);
        verifyNoInteractions(stepRepository, eventRecorder, idGenerator);
    }

    @Test
    void aReportWithoutAnAttemptNumberAcceptsTheLatestRunningAttempt() {
        FlowRunStepAttempt latest = attempt(256, "running");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(latest));

        assertSame(latest, lifecycle.runningAttempt(step("running"), null));
    }

    @Test
    void aReportWithTheCurrentAttemptNumberAcceptsItByNumericValue() {
        FlowRunStepAttempt latest = attempt(256, "running");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(latest));

        assertSame(latest, lifecycle.runningAttempt(step("running"), 256));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 255, 257})
    void aReportNamingAnotherAttemptKeepsTheConflictAndMessage(int number) {
        FlowRunStepAttempt latest = attempt(256, "running");
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(latest));

        BusinessException rejected = assertThrows(BusinessException.class,
            () -> lifecycle.runningAttempt(step("running"), number));

        assertEquals(ErrorCode.CONFLICT, rejected.getErrorCode());
        assertEquals("Attempt " + number + " is no longer running.", rejected.getMessage());
        verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("step-1");
        verifyNoMoreInteractions(attemptRepository);
        verifyNoInteractions(stepRepository, eventRecorder, idGenerator);
        assertEquals("running", latest.getStatus());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"planned", "failed", "completed", "Running"})
    void aNonRunningStepIsRejectedBeforeLoadingAnAttemptOrComparingTheReportNumber(String status) {
        BusinessException rejected = assertThrows(BusinessException.class,
            () -> lifecycle.runningAttempt(step(status), 255));

        assertEquals(ErrorCode.CONFLICT, rejected.getErrorCode());
        assertEquals("Only a running step can end.", rejected.getMessage());
        verifyNoInteractions(attemptRepository, stepRepository, eventRecorder, idGenerator);
    }

    @Test
    void aRunningStepWithNoAttemptKeepsTheInvariantErrorBeforeComparingTheReportNumber() {
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.empty());

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> lifecycle.runningAttempt(step("running"), 255));

        assertEquals("Running step has no attempt.", rejected.getMessage());
        verifyNoInteractions(stepRepository, eventRecorder, idGenerator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"failed", "completed", "Running"})
    void aNonRunningLatestAttemptKeepsTheInvariantErrorBeforeComparingTheReportNumber(String status) {
        FlowRunStepAttempt latest = attempt(256, status);
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("step-1")).thenReturn(Optional.of(latest));

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> lifecycle.runningAttempt(step("running"), 255));

        assertEquals("Running step's latest attempt is not running.", rejected.getMessage());
        verifyNoInteractions(stepRepository, eventRecorder, idGenerator);
    }

    private static FlowRunStep step(String status) {
        FlowRunStep step = new FlowRunStep();
        step.setStepId("step-1");
        step.setFlowRunId("run-1");
        step.setNodeId("node-1");
        step.setSourceConnectionId("connection-1");
        step.setSourceStepId("source-1");
        step.setStatus(status);
        step.setSequenceNo(7);
        step.setScheduledAt(OffsetDateTime.parse("2026-10-01T09:00:00+09:00"));
        step.setEndedAt(OffsetDateTime.parse("2026-10-01T09:05:00+09:00"));
        step.setInputSnapshot("{\"quantity\":123456789.123456789}");
        step.setOutputSnapshot("{\"accepted\":false}");
        step.setErrorCode("OLD_ERROR");
        step.setErrorMessage("Old failure.");
        return step;
    }

    private static FlowRunStepAttempt attempt(int number, String status) {
        FlowRunStepAttempt attempt = new FlowRunStepAttempt();
        attempt.setAttemptId("attempt-" + number);
        attempt.setStepId("step-1");
        attempt.setAttemptNo(number);
        attempt.setStatus(status);
        return attempt;
    }

    private static void assertStepMetadataPreserved(FlowRunStep step) {
        assertEquals("step-1", step.getStepId());
        assertEquals("run-1", step.getFlowRunId());
        assertEquals("node-1", step.getNodeId());
        assertEquals("connection-1", step.getSourceConnectionId());
        assertEquals("source-1", step.getSourceStepId());
        assertEquals(7, step.getSequenceNo());
        assertEquals(OffsetDateTime.parse("2026-10-01T09:00:00+09:00"), step.getScheduledAt());
        assertEquals("{\"quantity\":123456789.123456789}", step.getInputSnapshot());
        assertEquals("{\"accepted\":false}", step.getOutputSnapshot());
    }
}
