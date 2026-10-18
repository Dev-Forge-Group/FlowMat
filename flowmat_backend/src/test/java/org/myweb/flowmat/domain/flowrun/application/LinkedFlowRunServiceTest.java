package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class LinkedFlowRunServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private FlowRunRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private FlowRunEventRecorder eventRecorder;
    @Mock private IdGenerator idGenerator;
    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunService runService;
    @Mock private WorkflowProductionQuery workflowQuery;

    private LinkedFlowRunService service;

    @BeforeEach
    void setUp() {
        service = new LinkedFlowRunService(repository, eventRecorder, entityManager, idGenerator, stepRepository, runService,
            workflowQuery, MAPPER);
    }

    @Test
    void historicalRunWithUnsupportedTypeCanStillFinishWithoutCreatingAFlowRun() {
        when(repository.findLockedByProductionRunId("old-run")).thenReturn(Optional.empty());

        service.finishLinkedRun(run("old-run", "custom-legacy-type"), MAPPER.createObjectNode(), "u1");

        verify(repository, never()).save(any(FlowRun.class));
        verify(entityManager, never()).flush();
    }

    @Test
    void finishingARunThatNeverStartedStartsItFirstThenFinishesIt() {
        when(repository.findLockedByProductionRunId("run-1")).thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn("fr-1");
        ObjectNode output = MAPPER.createObjectNode().put("actualOutputQty", 7);

        service.finishLinkedRun(run("run-1", "actual"), output, "u2");

        ArgumentCaptor<FlowRun> saved = ArgumentCaptor.forClass(FlowRun.class);
        verify(repository).save(saved.capture());
        assertEquals("finished", saved.getValue().getStatus());
        assertEquals("run-1", saved.getValue().getProductionRunId());
        assertEquals("revision-1", saved.getValue().getWorkflowRevisionId());
        assertEquals(output.toString(), saved.getValue().getOutputPayload());
        verify(eventRecorder).record(eq("fr-1"), eq(null), eq("run_started"), any(), eq("u1"));
        verify(eventRecorder).record("fr-1", null, "run_finished", output, "u2");
    }

    @Test
    void finishingAnEndedRunIsAConflict() {
        FlowRun ended = new FlowRun();
        ended.setFlowRunId("fr-1");
        ended.setStatus("finished");
        when(repository.findLockedByProductionRunId("run-1")).thenReturn(Optional.of(ended));

        BusinessException conflict = assertThrows(BusinessException.class,
            () -> service.finishLinkedRun(run("run-1", "actual"), MAPPER.createObjectNode(), "u1"));

        assertEquals(ErrorCode.CONFLICT, conflict.getErrorCode());
        verify(repository, never()).save(any(FlowRun.class));
    }

    @Test
    void startingWithAnUnknownRunTypeIsRejectedBeforeAnythingIsWritten() {
        BusinessException rejected = assertThrows(BusinessException.class,
            () -> service.startLinkedRun(run("run-1", "custom-legacy-type")));

        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        verify(entityManager, never()).flush();
        verify(repository, never()).saveAndFlush(any(FlowRun.class));
    }

    private static LinkedRun run(String productionRunId, String runType) {
        return new LinkedRun("p1", "wf-1", "revision-1", productionRunId, runType, "u1",
            MAPPER.createObjectNode().put("plannedOutputQty", 10));
    }
}
