package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;

@ExtendWith(MockitoExtension.class)
class FlowRunTerminationTest {
    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-10-22T10:00:00.123456789+09:00");

    @Mock private FlowRunRepository runRepository;
    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunStepAttemptRepository attemptRepository;
    @Mock private FlowRunEventRecorder eventRecorder;

    private final ObjectMapper mapper = new ObjectMapper();
    private FlowRunTermination termination;

    @BeforeEach
    void setUp() {
        termination = new FlowRunTermination(runRepository, stepRepository, attemptRepository, eventRecorder,
            new FlowRunHttpInFlightRequests());
    }

    @ParameterizedTest
    @MethodSource("terminalActors")
    void endsOnlyActiveStepsInRepositoryOrderBeforeEndingTheRun(String status, String actor) {
        FlowRun run = run();
        FlowRunStep first = step("first", "running");
        FlowRunStep planned = step("planned", "planned");
        FlowRunStep last = step("last", "running");
        List<FlowRunStep> terminal = Stream.of("completed", "failed", "cancelled", "skipped")
            .map(value -> step(value, value)).toList();
        terminal.forEach(step -> step.setEndedAt(STARTED));
        FlowRunStepAttempt firstAttempt = attempt(first);
        FlowRunStepAttempt lastAttempt = attempt(last);
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(List.of(
            first, terminal.get(0), planned, terminal.get(1), terminal.get(2), terminal.get(3), last));
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("first")).thenReturn(Optional.of(firstAttempt));
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("last")).thenReturn(Optional.of(lastAttempt));
        JsonNode payload = mapper.createObjectNode().put("message", "  original message  ");
        doAnswer(invocation -> {
            assertEquals("running", run.getStatus());
            assertNull(run.getEndedAt());
            return null;
        }).when(eventRecorder).record(eq("run-1"), isNotNull(), anyString(), same(payload), eq(actor));

        termination.stop(run, status, "UPSTREAM", "  original message  ", payload, actor);

        String runningStatus = "failed".equals(status) ? "failed" : "cancelled";
        String runningCode = "failed".equals(status) ? "UPSTREAM" : "RUN_CANCELLED";
        String plannedCode = "failed".equals(status) ? "RUN_FAILED" : "RUN_CANCELLED";
        assertEquals(status, run.getStatus());
        assertNotNull(run.getEndedAt());
        assertEquals(ZoneOffset.UTC, run.getEndedAt().getOffset());
        assertEquals("input", run.getInputPayload());
        assertEquals("output", run.getOutputPayload());
        assertSame(STARTED, run.getStartedAt());
        for (FlowRunStep active : List.of(first, planned, last)) {
            assertEquals(active == planned ? "cancelled" : runningStatus, active.getStatus());
            assertEquals(active == planned ? plannedCode : runningCode, active.getErrorCode());
            assertEquals("  original message  ", active.getErrorMessage());
            assertSame(run.getEndedAt(), active.getEndedAt());
            assertSame(STARTED, active.getStartedAt());
            assertSame(STARTED, active.getScheduledAt());
            assertEquals("input", active.getInputSnapshot());
            assertEquals("output", active.getOutputSnapshot());
        }
        for (FlowRunStepAttempt attempt : List.of(firstAttempt, lastAttempt)) {
            assertEquals(runningStatus, attempt.getStatus());
            assertEquals(runningCode, attempt.getErrorCode());
            assertEquals("  original message  ", attempt.getErrorMessage());
            assertSame(run.getEndedAt(), attempt.getEndedAt());
            assertSame(STARTED, attempt.getStartedAt());
            assertSame(STARTED, attempt.getTimeoutAt());
            assertSame(STARTED, attempt.getRetryAt());
            assertSame(STARTED, attempt.getOverdueAt());
        }
        for (FlowRunStep ended : terminal) {
            assertEquals(ended.getStepId(), ended.getStatus());
            assertSame(STARTED, ended.getEndedAt());
            assertNull(ended.getErrorCode());
            assertNull(ended.getErrorMessage());
        }
        InOrder writes = inOrder(stepRepository, attemptRepository, runRepository, eventRecorder);
        writes.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        writes.verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("first");
        writes.verify(attemptRepository).save(firstAttempt);
        writes.verify(stepRepository).save(first);
        writes.verify(eventRecorder).record(eq("run-1"), eq("first"), eq("step_" + runningStatus), same(payload), eq(actor));
        writes.verify(stepRepository).save(planned);
        writes.verify(eventRecorder).record(eq("run-1"), eq("planned"), eq("step_cancelled"), same(payload), eq(actor));
        writes.verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("last");
        writes.verify(attemptRepository).save(lastAttempt);
        writes.verify(stepRepository).save(last);
        writes.verify(eventRecorder).record(eq("run-1"), eq("last"), eq("step_" + runningStatus), same(payload), eq(actor));
        writes.verify(runRepository).save(run);
        writes.verify(eventRecorder).record(eq("run-1"), eq((String) null), eq("run_" + status), same(payload), eq(actor));
        writes.verifyNoMoreInteractions();
        verifyNoMoreInteractions(stepRepository, attemptRepository, runRepository, eventRecorder);
    }

    @Test
    void aRunWithoutStepsStillEndsAndRecordsItsEvent() {
        FlowRun run = run();
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(List.of());
        JsonNode payload = mapper.createObjectNode();

        termination.stop(run, "cancelled", "RUN_CANCELLED", "stop", payload, "actor-1");

        assertEquals("cancelled", run.getStatus());
        InOrder calls = inOrder(stepRepository, runRepository, eventRecorder);
        calls.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        calls.verify(runRepository).save(run);
        calls.verify(eventRecorder).record("run-1", null, "run_cancelled", payload, "actor-1");
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(attemptRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"planned", "completed", "failed", "cancelled"})
    void anInvalidLatestAttemptKeepsTheErrorAndStopsBeforeLaterStepsOrTheRun(String attemptStatus) {
        FlowRun run = run();
        FlowRunStep earlier = step("earlier", "planned");
        FlowRunStep invalid = step("invalid", "running");
        FlowRunStep later = step("later", "planned");
        FlowRunStepAttempt attempt = attempt(invalid);
        attempt.setStatus(attemptStatus);
        JsonNode payload = mapper.createObjectNode();
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(List.of(earlier, invalid, later));
        when(attemptRepository.findTopByStepIdOrderByAttemptNoDesc("invalid"))
            .thenReturn(attemptStatus == null ? Optional.empty() : Optional.of(attempt));

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> termination.stop(run, "failed", "UPSTREAM", "problem", payload, null));

        assertEquals(attemptStatus == null ? "Running step has no attempt."
            : "Running step's latest attempt is not running.", rejected.getMessage());
        assertEquals("cancelled", earlier.getStatus());
        assertEquals("running", invalid.getStatus());
        assertNull(invalid.getEndedAt());
        assertEquals("planned", later.getStatus());
        assertEquals("running", run.getStatus());
        assertNull(run.getEndedAt());
        InOrder calls = inOrder(stepRepository, attemptRepository, eventRecorder);
        calls.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        calls.verify(stepRepository).save(earlier);
        calls.verify(eventRecorder).record("run-1", "earlier", "step_cancelled", payload, null);
        calls.verify(attemptRepository).findTopByStepIdOrderByAttemptNoDesc("invalid");
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(runRepository);
        verifyNoMoreInteractions(stepRepository, attemptRepository, eventRecorder);
    }

    @Test
    void aStepReadFailurePropagatesBeforeAnythingChanges() {
        FlowRun run = run();
        IllegalStateException unavailable = new IllegalStateException("storage unavailable");
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(IllegalStateException.class,
            () -> termination.stop(run, "failed", "UPSTREAM", null, null, null)));

        assertEquals("running", run.getStatus());
        assertNull(run.getEndedAt());
        verifyNoInteractions(attemptRepository, runRepository, eventRecorder);
    }

    @Test
    void nullMessageAndPayloadRemainNullInBothStepAndRunEvents() {
        FlowRun run = run();
        FlowRunStep planned = step("planned", "planned");
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(List.of(planned));

        termination.stop(run, "failed", "UPSTREAM", null, null, null);

        assertNull(planned.getErrorMessage());
        InOrder calls = inOrder(stepRepository, runRepository, eventRecorder);
        calls.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        calls.verify(stepRepository).save(planned);
        calls.verify(eventRecorder).record("run-1", "planned", "step_cancelled", null, null);
        calls.verify(runRepository).save(run);
        calls.verify(eventRecorder).record("run-1", null, "run_failed", null, null);
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(attemptRepository);
    }

    private static Stream<Arguments> terminalActors() {
        return Stream.of("failed", "cancelled")
            .flatMap(status -> Stream.of(null, "", " actor-1 ").map(actor -> Arguments.of(status, actor)));
    }

    private static FlowRun run() {
        FlowRun run = new FlowRun();
        run.setFlowRunId("run-1");
        run.setStatus("running");
        run.setStartedAt(STARTED);
        run.setInputPayload("input");
        run.setOutputPayload("output");
        return run;
    }

    private static FlowRunStep step(String id, String status) {
        FlowRunStep step = new FlowRunStep();
        step.setStepId(id);
        step.setFlowRunId("run-1");
        step.setStatus(status);
        step.setStartedAt(STARTED);
        step.setScheduledAt(STARTED);
        step.setInputSnapshot("input");
        step.setOutputSnapshot("output");
        return step;
    }

    private static FlowRunStepAttempt attempt(FlowRunStep step) {
        FlowRunStepAttempt attempt = new FlowRunStepAttempt();
        attempt.setAttemptId("attempt-" + step.getStepId());
        attempt.setStepId(step.getStepId());
        attempt.setAttemptNo(3);
        attempt.setStatus("running");
        attempt.setStartedAt(STARTED);
        attempt.setTimeoutAt(STARTED);
        attempt.setRetryAt(STARTED);
        attempt.setOverdueAt(STARTED);
        return attempt;
    }
}
