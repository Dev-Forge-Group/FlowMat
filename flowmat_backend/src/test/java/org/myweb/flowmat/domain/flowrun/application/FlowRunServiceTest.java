package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFinishRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunCancelRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStartRequest;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class FlowRunServiceTest {

    @Mock private FlowRunRepository runRepository;
    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunStepAttemptRepository attemptRepository;
    @Mock private FlowRunEventRecorder eventRecorder;
    @Mock private WorkflowProductionQuery workflowQuery;
    @Mock private ProjectAccessService accessService;
    @Mock private WorkflowAccess workflowAccess;
    @Mock private IdGenerator idGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowRunService service;

    @BeforeEach
    void setUp() {
        service = new FlowRunService(runRepository, stepRepository, eventRecorder, workflowQuery,
            accessService, workflowAccess, idGenerator, objectMapper,
            new FlowRunTermination(runRepository, stepRepository, attemptRepository, eventRecorder));
    }

    @Test
    void startsOnlyPublishedRevisionWithAuthenticatedActorAndFrozenInputs() throws Exception {
        when(workflowAccess.requireWriteAndLock("workflow-1")).thenReturn(new WorkflowAccessView("workflow-1", "project-1"));
        when(workflowQuery.findRevision("revision-1", "workflow-1"))
            .thenReturn(Optional.of(new WorkflowProductionQuery.Revision("revision-1", "published", null, 1)));
        when(accessService.requireCurrentUserId()).thenReturn("authenticated-user");
        when(idGenerator.generate()).thenReturn("run-1");
        when(runRepository.saveAndFlush(any(FlowRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.start(new FlowRunStartRequest("workflow-1", "revision-1", "test",
            objectMapper.readTree("{\"lot\":\"L-1\"}")));

        assertEquals("revision-1", response.workflowRevisionId());
        assertEquals("authenticated-user", response.requestedBy());
        assertEquals("L-1", response.inputPayload().path("lot").asText());
        assertEquals("running", response.status());
        assertNotNull(response.startedAt());
        verify(workflowAccess).requireWriteAndLock("workflow-1");
    }

    @Test
    void refusesRetiredRevisionBeforeSavingRun() {
        when(workflowAccess.requireWriteAndLock("workflow-1")).thenReturn(new WorkflowAccessView("workflow-1", null));
        when(workflowQuery.findRevision("revision-1", "workflow-1"))
            .thenReturn(Optional.of(new WorkflowProductionQuery.Revision("revision-1", "retired", null, 1)));

        assertThrows(BusinessException.class,
            () -> service.start(new FlowRunStartRequest("workflow-1", "revision-1", "test", null)));
        verify(runRepository, never()).save(any());
    }

    @Test
    void finishRequiresWriteAccessAndRejectsSecondCompletion() throws Exception {
        FlowRun run = new FlowRun();
        run.setFlowRunId("run-1");
        run.setProjectId("project-1");
        run.setStatus("running");
        run.setInputPayload("{}");
        when(runRepository.findLockedByFlowRunId("run-1")).thenReturn(Optional.of(run));
        when(runRepository.save(any(FlowRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.finish("run-1", new FlowRunFinishRequest(objectMapper.readTree("{\"ok\":true}")));

        verify(accessService).requireProjectWriteAccess("project-1");
        assertEquals("finished", response.status());
        assertEquals(true, response.outputPayload().path("ok").asBoolean());
        assertNotNull(response.endedAt());
        assertThrows(BusinessException.class,
            () -> service.finish("run-1", new FlowRunFinishRequest(null)));
    }

    @Test
    void cancelLocksAndAuthorizesBeforeEndingTheRunAndMappingItsPayloads() {
        FlowRun run = runningRun();
        run.setInputPayload("{\"quantity\":123456789.123456789}");
        run.setOutputPayload("{\"done\":true}");
        when(runRepository.findLockedByFlowRunId("run-1")).thenReturn(Optional.of(run));
        when(accessService.requireCurrentUserId()).thenReturn("actor-1");
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(List.of());

        var response = service.cancel("run-1", new FlowRunCancelRequest("  stopped by owner  "));

        assertEquals("cancelled", response.status());
        assertNotNull(response.endedAt());
        assertEquals(new BigDecimal("123456789.123456789"), response.inputPayload().path("quantity").decimalValue());
        assertEquals(true, response.outputPayload().path("done").asBoolean());
        InOrder calls = inOrder(runRepository, accessService, stepRepository, eventRecorder);
        calls.verify(runRepository).findLockedByFlowRunId("run-1");
        calls.verify(accessService).requireProjectWriteAccess("project-1");
        calls.verify(accessService).requireCurrentUserId();
        calls.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        calls.verify(runRepository).save(run);
        calls.verify(eventRecorder).record("run-1", null, "run_cancelled",
            objectMapper.createObjectNode().put("reason", "stopped by owner"), "actor-1");
        calls.verifyNoMoreInteractions();
        verifyNoInteractions(attemptRepository);
    }

    @Test
    void failTrimsOnlyTheCodeAndKeepsANullMessageAndRawActor() {
        FlowRun run = runningRun();
        when(runRepository.findLockedByFlowRunId("run-1")).thenReturn(Optional.of(run));
        when(accessService.requireCurrentUserId()).thenReturn(" actor-1 ");

        var response = service.fail("run-1", new FlowRunFailRequest("  UPSTREAM  ", null));

        assertEquals("failed", response.status());
        verify(eventRecorder).record("run-1", null, "run_failed", objectMapper.createObjectNode()
            .put("errorCode", "UPSTREAM").putNull("errorMessage"), " actor-1 ");
        verifyNoInteractions(attemptRepository);
    }

    @Test
    void failLockedKeepsTheAuthorizedCallersRunAndSystemActorWithoutLookingThemUpAgain() {
        FlowRun run = runningRun();

        var response = service.failLocked(run, "TIMEOUT", "too late", null);

        assertEquals("failed", response.status());
        verify(runRepository).save(run);
        verify(runRepository, never()).findLockedByFlowRunId(any());
        verify(eventRecorder).record("run-1", null, "run_failed", objectMapper.createObjectNode()
            .put("errorCode", "TIMEOUT").put("errorMessage", "too late"), null);
        verifyNoInteractions(accessService, workflowAccess, workflowQuery, attemptRepository);
    }

    @Test
    void deniedWriteAccessStopsBeforeActorLookupAndTermination() {
        FlowRun run = runningRun();
        when(runRepository.findLockedByFlowRunId("run-1")).thenReturn(Optional.of(run));
        IllegalStateException denied = new IllegalStateException("authorization blocked");
        doThrow(denied).when(accessService).requireProjectWriteAccess("project-1");

        assertSame(denied, assertThrows(IllegalStateException.class,
            () -> service.cancel("run-1", new FlowRunCancelRequest("stop"))));

        assertEquals("running", run.getStatus());
        verify(accessService, never()).requireCurrentUserId();
        verify(runRepository, never()).save(any());
        verifyNoInteractions(stepRepository, attemptRepository, eventRecorder);
    }

    private FlowRun runningRun() {
        FlowRun run = new FlowRun();
        run.setFlowRunId("run-1");
        run.setProjectId("project-1");
        run.setStatus("running");
        return run;
    }
}
