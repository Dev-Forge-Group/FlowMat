package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime.WorkingStart;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunEvent;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepSourceRepository;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class FlowRunStepAvailabilityTest {
    private static final OffsetDateTime ENDED = OffsetDateTime.parse("2026-10-22T09:00:00.123456789+09:00");

    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunStepSourceRepository sourceRepository;
    @Mock private FlowRunRepository runRepository;
    @Mock private FlowRunEventRepository eventRepository;
    @Mock private LinkedRunWorkingTime workingTime;
    @Mock private FlowRunStepAttemptRepository attemptRepository;
    @Mock private FlowRunEventRecorder eventRecorder;
    @Mock private IdGenerator idGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowRunStepAvailability availability;

    @BeforeEach
    void setUp() {
        availability = new FlowRunStepAvailability(stepRepository, sourceRepository, runRepository,
            eventRepository, workingTime, objectMapper);
    }

    @Test
    void aSourceWithoutAnEndOrCarriedTimeHasNoReadyTimeEvenWhenThereIsADelay() {
        FlowRunStep source = step("source-step", "source", null);
        source.setScheduledAt(ENDED.plusHours(1));

        assertNull(availability.readyAt(graph("process", BigDecimal.ONE, true), source, "connection-1"));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noDelayLeavesAnOrdinaryStepImmediateWithoutReadingTheRun(boolean working) {
        FlowRunStep source = step("source-step", "source", ENDED);
        source.setScheduledAt(ENDED.plusHours(1));

        assertNull(availability.readyAt(graph("process", BigDecimal.ZERO, working), source, "connection-1"));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exclusive", "join", "split"})
    void aGatewayCarriesItsScheduledTimeWithoutANewDelay(String nodeType) {
        FlowRunStep source = step("source-step", "source", null);
        OffsetDateTime carried = ENDED.plusHours(1);
        source.setScheduledAt(carried);

        assertEquals(carried.truncatedTo(ChronoUnit.MICROS),
            availability.readyAt(graph(nodeType, null, true), source, "connection-1"));

        assertSame(carried, source.getScheduledAt());
        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exclusive", "join", "split"})
    void aGatewayWithoutACarriedTimeAndWithoutADelayRemainsImmediate(String nodeType) {
        assertNull(availability.readyAt(graph(nodeType, null, false),
            step("source-step", "source", ENDED), "connection-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exclusive", "join", "split"})
    void aGatewayAddsTheDelayToItsCarriedTimeInsteadOfItsLaterEnd(String nodeType) {
        FlowRunStep source = step("source-step", "source", ENDED.plusHours(2));
        OffsetDateTime carried = ENDED.plusHours(1);
        source.setScheduledAt(carried);

        assertEquals(carried.plusSeconds(5).truncatedTo(ChronoUnit.MICROS),
            availability.readyAt(graph(nodeType, BigDecimal.valueOf(5), false), source, "connection-1"));

        verifyNoInteractions(runRepository, workingTime);
    }

    @Test
    void fractionalDelayIsAddedBeforeMicrosecondTruncationAndIgnoresAnOrdinarySourcesSchedule() {
        OffsetDateTime ended = OffsetDateTime.parse("2026-10-22T23:59:59.999999001+09:00");
        FlowRunStep source = step("source-step", "source", ended);
        source.setScheduledAt(ended.plusDays(1));

        OffsetDateTime ready = availability.readyAt(graph("process", new BigDecimal("0.0000009999"), false),
            source, "connection-1");

        assertEquals(OffsetDateTime.parse("2026-10-23T00:00:00+09:00"), ready);
        assertSame(ended, source.getEndedAt());
        verifyNoInteractions(runRepository, workingTime);
    }

    @Test
    void workingDelayRestoresTheSourcesOffsetBeforeTruncatingTheResult() {
        Duration delay = Duration.ofNanos(1_234_567_891L);
        WorkingStart worked = new WorkingStart(ENDED.plusHours(3).withOffsetSameInstant(ZoneOffset.ofHours(-5)),
            "equipment-1", List.of("worker-1"));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("production-1")));
        when(workingTime.afterWorking("production-1", ENDED, delay)).thenReturn(Optional.of(worked));

        OffsetDateTime ready = availability.readyAt(graph("process", new BigDecimal("1.2345678919"), true),
            step("source-step", "source", ENDED), "connection-1");

        assertEquals(ENDED.plusHours(3).truncatedTo(ChronoUnit.MICROS), ready);
        InOrder reads = inOrder(runRepository, workingTime);
        reads.verify(runRepository).findById("run-1");
        reads.verify(workingTime).afterWorking("production-1", ENDED, delay);
        reads.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unlinked", "unavailable"})
    void unavailableWorkingTimeKeepsTheElapsedDelay(String kind) {
        Optional<FlowRun> run = "missing".equals(kind) ? Optional.empty()
            : Optional.of(run("unlinked".equals(kind) ? null : "production-1"));
        when(runRepository.findById("run-1")).thenReturn(run);
        if ("unavailable".equals(kind)) {
            when(workingTime.afterWorking("production-1", ENDED, Duration.ofSeconds(5))).thenReturn(Optional.empty());
        }

        assertEquals(ENDED.plusSeconds(5).truncatedTo(ChronoUnit.MICROS),
            availability.readyAt(graph("process", BigDecimal.valueOf(5), true),
                step("source-step", "source", ENDED), "connection-1"));

        verify(runRepository).findById("run-1");
        if (!"unavailable".equals(kind)) verifyNoInteractions(workingTime);
    }

    @Test
    void aJoinWithoutArrivalsHasNoReadyTime() {
        when(sourceRepository.findAllByStepIdOrderByArrivalNoAsc("join-step")).thenReturn(List.of());

        assertNull(availability.joinReadyAt(graph("process", BigDecimal.ONE, false), join()));

        verifyNoInteractions(stepRepository, runRepository, workingTime);
    }

    @Test
    void aJoinReadsEveryArrivalInOrderAndCanUseALaterUndelayedEnd() {
        FlowRunStep delayed = step("delayed-step", "source", ENDED);
        FlowRunStep plain = step("plain-step", "plain-source",
            ENDED.plusMinutes(10).withOffsetSameInstant(ZoneOffset.ofHours(-5)));
        when(sourceRepository.findAllByStepIdOrderByArrivalNoAsc("join-step")).thenReturn(List.of(
            arrival("missing", "connection-1"), arrival("delayed-step", "connection-1"),
            arrival("plain-step", "connection-plain"), arrival("delayed-step", "connection-1")));
        when(stepRepository.findById("missing")).thenReturn(Optional.empty());
        when(stepRepository.findById("delayed-step")).thenReturn(Optional.of(delayed));
        when(stepRepository.findById("plain-step")).thenReturn(Optional.of(plain));

        assertEquals(plain.getEndedAt().truncatedTo(ChronoUnit.MICROS),
            availability.joinReadyAt(graph("process", BigDecimal.valueOf(5), false), join()));

        InOrder reads = inOrder(sourceRepository, stepRepository);
        reads.verify(sourceRepository).findAllByStepIdOrderByArrivalNoAsc("join-step");
        reads.verify(stepRepository).findById("missing");
        reads.verify(stepRepository).findById("delayed-step");
        reads.verify(stepRepository).findById("plain-step");
        reads.verify(stepRepository).findById("delayed-step");
        reads.verifyNoMoreInteractions();
        verifyNoInteractions(runRepository, workingTime);
    }

    @Test
    void aJoinWithNoDelayedArrivalHasNoReadyTimeEvenWhenSourcesEnded() {
        when(sourceRepository.findAllByStepIdOrderByArrivalNoAsc("join-step")).thenReturn(List.of(
            arrival("missing", "connection-1"), arrival("plain-step", "connection-plain"),
            arrival("unfinished", "connection-plain")));
        when(stepRepository.findById("missing")).thenReturn(Optional.empty());
        when(stepRepository.findById("plain-step"))
            .thenReturn(Optional.of(step("plain-step", "plain-source", ENDED)));
        when(stepRepository.findById("unfinished"))
            .thenReturn(Optional.of(step("unfinished", "plain-source", null)));

        assertNull(availability.joinReadyAt(graph("process", BigDecimal.valueOf(5), false), join()));
    }

    @Test
    void aJoinCarriesAGatewaysTimeEvenWithoutANewConnectionDelay() {
        FlowRunStep source = step("gateway-step", "source", ENDED.plusHours(2));
        source.setScheduledAt(ENDED.plusHours(1));
        when(sourceRepository.findAllByStepIdOrderByArrivalNoAsc("join-step"))
            .thenReturn(List.of(arrival("gateway-step", "connection-1")));
        when(stepRepository.findById("gateway-step")).thenReturn(Optional.of(source));

        assertEquals(source.getScheduledAt().truncatedTo(ChronoUnit.MICROS),
            availability.joinReadyAt(graph("exclusive", null, false), join()));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void equalArrivalInstantsRetainTheFirstArrivalsOffset(boolean delayedFirst) {
        OffsetDateTime delayedReady = ENDED.plusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        FlowRunStep delayed = step("delayed-step", "source", ENDED);
        FlowRunStep plain = step("plain-step", "plain-source",
            delayedReady.withOffsetSameInstant(ZoneOffset.ofHours(-5)));
        FlowRunStepSource delayedArrival = arrival("delayed-step", "connection-1");
        FlowRunStepSource plainArrival = arrival("plain-step", "connection-plain");
        when(sourceRepository.findAllByStepIdOrderByArrivalNoAsc("join-step"))
            .thenReturn(delayedFirst ? List.of(delayedArrival, plainArrival) : List.of(plainArrival, delayedArrival));
        when(stepRepository.findById("delayed-step")).thenReturn(Optional.of(delayed));
        when(stepRepository.findById("plain-step")).thenReturn(Optional.of(plain));

        assertEquals(delayedFirst ? delayedReady : plain.getEndedAt(),
            availability.joinReadyAt(graph("process", BigDecimal.valueOf(5), false), join()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workingStartKeepsTheProvidersValueOrItsAbsenceWithoutNormalizingIt(boolean held) {
        WorkingStart result = new WorkingStart(ENDED.withOffsetSameInstant(ZoneOffset.ofHours(-5)),
            "equipment-1", List.of("worker-2", "worker-1"));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("production-1")));
        when(workingTime.workingStart("production-1", ENDED))
            .thenReturn(held ? Optional.of(result) : Optional.empty());

        assertSame(held ? result : null, availability.workingStart("run-1", ENDED));

        InOrder reads = inOrder(runRepository, workingTime);
        reads.verify(runRepository).findById("run-1");
        reads.verify(workingTime).workingStart("production-1", ENDED);
        reads.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workingStartDoesNotAskTheProviderForAMissingOrUnlinkedRun(boolean missing) {
        when(runRepository.findById("run-1"))
            .thenReturn(missing ? Optional.empty() : Optional.of(run(null)));

        assertNull(availability.workingStart("run-1", ENDED));

        verifyNoInteractions(workingTime);
    }

    @Test
    void anUnlinkedRunCannotStartEarlyAndDoesNotReadEvents() {
        assertFalse(availability.mayStartEarly(run(null), step("step-1", "target", null), ENDED));

        verifyNoInteractions(eventRepository, runRepository, workingTime);
    }

    @Test
    void aLinkedRunWithoutAScheduledEventCannotStartEarly() {
        when(eventRepository.findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc("step-1", "step_scheduled"))
            .thenReturn(Optional.empty());

        assertFalse(availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{}", "{\"readyAt\":null}", "[]"})
    void aLatestScheduledEventWithoutAHeldReadyTimeCannotStartEarly(String payload) {
        latestScheduledEvent(payload);

        assertFalse(availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        verify(eventRepository).findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc("step-1", "step_scheduled");
        verifyNoMoreInteractions(eventRepository);
        verifyNoInteractions(runRepository, workingTime);
    }

    @Test
    void aReadyTimeAfterNowCannotStartEarlyAndDoesNotAskForWorkingTime() {
        latestScheduledEvent(objectMapper.createObjectNode()
            .put("readyAt", ENDED.plusNanos(1).withOffsetSameInstant(ZoneOffset.ofHours(-5)).toString()).toString());

        assertFalse(availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aHeldStepReadyAtNowUsesTheCallersProductionIdAndExactNowToCheckWorkingTime(boolean stillHeld) {
        latestScheduledEvent(objectMapper.createObjectNode()
            .put("readyAt", ENDED.withOffsetSameInstant(ZoneOffset.ofHours(-5)).toString()).toString());
        when(workingTime.workingStart("production-1", ENDED)).thenReturn(stillHeld
            ? Optional.of(new WorkingStart(ENDED.plusHours(1), "equipment-1", List.of())) : Optional.empty());

        assertEquals(!stillHeld,
            availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        InOrder reads = inOrder(eventRepository, workingTime);
        reads.verify(eventRepository)
            .findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc("step-1", "step_scheduled");
        reads.verify(workingTime).workingStart(eq("production-1"), same(ENDED));
        reads.verifyNoMoreInteractions();
        verifyNoInteractions(runRepository);
    }

    @Test
    void malformedHeldEventJsonKeepsTheSnapshotReadError() {
        latestScheduledEvent("{");

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
            () -> availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        assertEquals("Flow run step snapshot could not be read.", rejected.getMessage());
        assertTrue(rejected.getCause() instanceof JsonProcessingException);
        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"readyAt\":\"\"}", "{\"readyAt\":\"later\"}",
        "{\"readyAt\":\" 2026-10-22T09:00:00Z\"}", "{\"readyAt\":42}"})
    void anInvalidHeldReadyTimeKeepsTheDateParserError(String payload) {
        latestScheduledEvent(payload);

        assertThrows(DateTimeParseException.class,
            () -> availability.mayStartEarly(run("production-1"), step("step-1", "target", null), ENDED));

        verifyNoInteractions(runRepository, workingTime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"equipment", "crew", "both", "empty"})
    void routerKeepsHeldEventFieldsAndPersistsTheScheduleBeforeRecordingIt(String metadata) {
        FlowRunGraph graph = graph("process", new BigDecimal("1.2345678919"), false);
        FlowRunStep source = step("source-step", "source", ENDED);
        OffsetDateTime ready = ENDED.plusNanos(1_234_567_891L).truncatedTo(ChronoUnit.MICROS);
        boolean equipment = "equipment".equals(metadata) || "both".equals(metadata);
        boolean crew = "crew".equals(metadata) || "both".equals(metadata);
        WorkingStart held = new WorkingStart(ENDED.plusHours(3).withOffsetSameInstant(ZoneOffset.ofHours(-5)),
            equipment ? "equipment-1" : null, crew ? List.of("worker-2", "worker-1") : List.of());
        OffsetDateTime scheduled = held.at().withOffsetSameInstant(ready.getOffset()).truncatedTo(ChronoUnit.MICROS);
        when(stepRepository.countByFlowRunId("run-1")).thenReturn(2L);
        when(idGenerator.generate()).thenReturn("new-step");
        when(stepRepository.findById("source-step")).thenReturn(Optional.of(source));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("production-1")));
        when(workingTime.workingStart("production-1", ready)).thenReturn(Optional.of(held));
        FlowRunRouter router = new FlowRunRouter(stepRepository, attemptRepository, sourceRepository,
            eventRecorder, idGenerator, objectMapper, availability);
        ObjectNode input = objectMapper.createObjectNode().put("quantity", new BigDecimal("123456789.123456789"));

        assertNull(router.follow("run-1", graph, "source-step",
            List.of(new FlowRunGraph.Decision("connection-1", "target", true)), input, "actor-1", true));

        ObjectNode payload = objectMapper.createObjectNode().put("scheduledAt", scheduled.toString())
            .put("reason", "connection_delay").put("connectionId", "connection-1")
            .put("delaySeconds", new BigDecimal("1.2345678919")).put("readyAt", ready.toString());
        if (equipment) payload.put("equipmentId", "equipment-1");
        if (crew) payload.set("crew", objectMapper.valueToTree(held.crew()));
        ArgumentCaptor<FlowRunStep> saved = ArgumentCaptor.forClass(FlowRunStep.class);
        InOrder calls = inOrder(stepRepository, idGenerator, eventRecorder, runRepository, workingTime);
        calls.verify(stepRepository).countByFlowRunId("run-1");
        calls.verify(idGenerator).generate();
        calls.verify(stepRepository).saveAndFlush(any(FlowRunStep.class));
        calls.verify(eventRecorder).record("run-1", "new-step", "step_created", input, "actor-1");
        calls.verify(stepRepository).findById("source-step");
        calls.verify(runRepository).findById("run-1");
        calls.verify(workingTime).workingStart("production-1", ready);
        calls.verify(stepRepository).saveAndFlush(saved.capture());
        calls.verify(eventRecorder).record("run-1", "new-step", "step_scheduled", payload, "actor-1");
        calls.verifyNoMoreInteractions();
        assertEquals(scheduled, saved.getValue().getScheduledAt());
        assertEquals("planned", saved.getValue().getStatus());
        verifyNoInteractions(attemptRepository, sourceRepository, eventRepository);
    }

    private FlowRunGraph graph(String sourceType, BigDecimal delay, boolean working) {
        ObjectNode snapshot = objectMapper.createObjectNode().put("schemaVersion", 3);
        var processes = snapshot.putArray("processes");
        processes.addObject().put("processId", "prior");
        processes.addObject().put("processId", "source").put("nodeType", sourceType);
        processes.addObject().put("processId", "plain-source");
        processes.addObject().put("processId", "target");
        var connections = snapshot.putArray("connections");
        connections.addObject().put("connectionId", "prior-source").put("fromProcessId", "prior")
            .put("toProcessId", "source");
        ObjectNode connection = connections.addObject().put("connectionId", "connection-1")
            .put("fromProcessId", "source").put("toProcessId", "target");
        if (delay != null) connection.put("delayTimeSec", delay);
        if (working) connection.put("delayBasis", "working");
        connections.addObject().put("connectionId", "connection-plain").put("fromProcessId", "plain-source")
            .put("toProcessId", "target");
        return FlowRunGraph.from(snapshot);
    }

    private void latestScheduledEvent(String payload) {
        FlowRunEvent event = new FlowRunEvent();
        event.setPayloadJson(payload);
        when(eventRepository.findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc("step-1", "step_scheduled"))
            .thenReturn(Optional.of(event));
    }

    private static FlowRun run(String productionId) {
        FlowRun run = new FlowRun();
        run.setFlowRunId("run-1");
        run.setProductionRunId(productionId);
        return run;
    }

    private static FlowRunStep step(String id, String nodeId, OffsetDateTime endedAt) {
        FlowRunStep step = new FlowRunStep();
        step.setStepId(id);
        step.setFlowRunId("run-1");
        step.setNodeId(nodeId);
        step.setEndedAt(endedAt);
        return step;
    }

    private static FlowRunStep join() {
        return step("join-step", "target", null);
    }

    private static FlowRunStepSource arrival(String sourceStepId, String connectionId) {
        FlowRunStepSource source = new FlowRunStepSource();
        source.setSourceStepId(sourceStepId);
        source.setConnectionId(connectionId);
        return source;
    }
}
