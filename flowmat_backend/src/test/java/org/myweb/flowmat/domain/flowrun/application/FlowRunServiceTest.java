package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFinishRequest;
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
        service = new FlowRunService(runRepository, stepRepository, attemptRepository,
            eventRecorder, workflowQuery,
            accessService, workflowAccess, idGenerator, objectMapper);
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
}
