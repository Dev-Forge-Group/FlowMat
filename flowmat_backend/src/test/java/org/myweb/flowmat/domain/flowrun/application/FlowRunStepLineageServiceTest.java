package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepLineageResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepResponse;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepSourceRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class FlowRunStepLineageServiceTest {

    @Mock private FlowRunRepository runRepository;
    @Mock private FlowRunStepRepository stepRepository;
    @Mock private FlowRunStepAttemptRepository attemptRepository;
    @Mock private FlowRunEventRepository eventRepository;
    @Mock private FlowRunEventRecorder eventRecorder;
    @Mock private WorkflowProductionQuery workflowQuery;
    @Mock private ProjectAccessService accessService;
    @Mock private IdGenerator idGenerator;
    @Mock private FlowRunService runService;
    @Mock private FlowRunRouter router;
    @Mock private FlowRunStepSourceRepository sourceRepository;
    @Mock private JdbcTemplate jdbc;
    @Mock private FlowRunStepAttemptLifecycle attemptLifecycle;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FlowRunStepService service;

    @BeforeEach
    void setUp() {
        service = new FlowRunStepService(runRepository, stepRepository, attemptRepository, eventRepository,
            eventRecorder, workflowQuery, accessService, idGenerator, objectMapper, runService, router,
            sourceRepository, jdbc, attemptLifecycle);
    }

    @Test
    void joinLineageUsesAllArrivalsAndReturnsEachRelativeInSequenceOrder() {
        FlowRunStep start = step("start", 1, null);
        FlowRunStep left = step("left", 2, "start");
        FlowRunStep right = step("right", 3, "start");
        FlowRunStep unrelated = step("unrelated", 4, null);
        FlowRunStep selected = step("selected", 5, "unrelated");
        FlowRunStep firstChild = step("first-child", 6, "selected");
        FlowRunStep secondChild = step("second-child", 7, "selected");
        FlowRunStep joinedAgain = step("joined-again", 8, null);
        FlowRunStep tail = step("tail", 9, "joined-again");
        readableSteps(List.of(start, left, right, unrelated, selected, firstChild, secondChild, joinedAgain, tail),
            List.of(arrival("joined-again", 1, "second-child"), arrival("joined-again", 2, "first-child"),
                arrival("selected", 1, "right"), arrival("selected", 2, "left")));

        FlowRunStepLineageResponse response = service.lineage("run-1", "selected");

        assertEquals("selected", response.step().stepId());
        assertEquals("unrelated", response.step().sourceStepId());
        assertEquals(List.of("start", "left", "right"), ids(response.ancestors()));
        assertEquals(List.of("first-child", "second-child", "joined-again", "tail"), ids(response.descendants()));
        assertNull(response.step().joinWaiting());
        response.ancestors().forEach(relative -> assertNull(relative.joinWaiting()));
        response.descendants().forEach(relative -> assertNull(relative.joinWaiting()));

        InOrder reads = inOrder(runRepository, accessService, stepRepository, sourceRepository);
        reads.verify(runRepository).findById("run-1");
        reads.verify(accessService).requireProjectReadAccess("project-1");
        reads.verify(stepRepository).findAllByFlowRunIdOrderBySequenceNoAsc("run-1");
        reads.verify(sourceRepository).findAllByFlowRunIdOrderByStepIdAscArrivalNoAsc("run-1");
    }

    @Test
    void selectedStepKeepsResponseFieldsAndExactJsonSnapshots() throws Exception {
        FlowRunStep selected = step("selected", 7, null);
        selected.setNodeId("node-7");
        selected.setSourceConnectionId("connection-7");
        selected.setStatus("failed");
        selected.setScheduledAt(OffsetDateTime.parse("2026-10-01T09:00:00+09:00"));
        selected.setStartedAt(OffsetDateTime.parse("2026-10-01T09:01:00+09:00"));
        selected.setEndedAt(OffsetDateTime.parse("2026-10-01T09:02:00+09:00"));
        selected.setInputSnapshot("{\"quantity\":123456789.123456789,\"lot\":\"L-1\"}");
        selected.setOutputSnapshot("{\"accepted\":false,\"items\":[1,2]}");
        selected.setErrorCode("INSPECTION_FAILED");
        selected.setErrorMessage("Inspection did not pass.");
        readableSteps(List.of(selected), List.of());

        FlowRunStepLineageResponse response = service.lineage("run-1", "selected");

        assertEquals(new FlowRunStepResponse("selected", "run-1", "node-7", "connection-7", null,
            "failed", 7, selected.getScheduledAt(), selected.getStartedAt(), selected.getEndedAt(),
            objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(selected.getInputSnapshot()),
            objectMapper.readTree(selected.getOutputSnapshot()), "INSPECTION_FAILED", "Inspection did not pass.", null),
            response.step());
        assertEquals(List.of(), response.ancestors());
        assertEquals(List.of(), response.descendants());
    }

    @Test
    void deniedReadAccessStopsBeforeLoadingStepsOrArrivals() {
        readableRun();
        BusinessException denial = new BusinessException(ErrorCode.FORBIDDEN);
        when(accessService.requireProjectReadAccess("project-1")).thenThrow(denial);

        BusinessException rejected = assertThrows(BusinessException.class,
            () -> service.lineage("run-1", "selected"));

        assertSame(denial, rejected);
        verifyNoInteractions(stepRepository, sourceRepository);
    }

    @Test
    void unknownSelectedStepIsNotFound() {
        readableSteps(List.of(step("existing", 1, null)), List.of());

        BusinessException missing = assertThrows(BusinessException.class,
            () -> service.lineage("run-1", "missing"));

        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    @Test
    void missingAncestorIsAConflict() {
        readableSteps(List.of(step("selected", 1, "missing-parent")), List.of());

        BusinessException invalid = assertThrows(BusinessException.class,
            () -> service.lineage("run-1", "selected"));

        assertEquals(ErrorCode.CONFLICT, invalid.getErrorCode());
        assertEquals("Flow run step lineage is invalid.", invalid.getMessage());
    }

    @Test
    void cycleThroughSelectedStepIsAConflict() {
        readableSteps(List.of(step("parent", 1, "selected"), step("selected", 2, "parent")), List.of());

        BusinessException invalid = assertThrows(BusinessException.class,
            () -> service.lineage("run-1", "selected"));

        assertEquals(ErrorCode.CONFLICT, invalid.getErrorCode());
        assertEquals("Flow run step lineage is invalid.", invalid.getMessage());
    }

    private void readableSteps(List<FlowRunStep> steps, List<FlowRunStepSource> sources) {
        readableRun();
        when(stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc("run-1")).thenReturn(steps);
        when(sourceRepository.findAllByFlowRunIdOrderByStepIdAscArrivalNoAsc("run-1")).thenReturn(sources);
    }

    private void readableRun() {
        FlowRun run = new FlowRun();
        run.setFlowRunId("run-1");
        run.setProjectId("project-1");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
    }

    private static FlowRunStep step(String id, int sequence, String parentId) {
        FlowRunStep step = new FlowRunStep();
        step.setStepId(id);
        step.setFlowRunId("run-1");
        step.setNodeId("node-" + id);
        step.setStatus("completed");
        step.setSequenceNo(sequence);
        step.setSourceStepId(parentId);
        return step;
    }

    private static FlowRunStepSource arrival(String stepId, int number, String parentId) {
        FlowRunStepSource source = new FlowRunStepSource();
        source.setStepSourceId(stepId + "-" + number);
        source.setFlowRunId("run-1");
        source.setStepId(stepId);
        source.setArrivalNo(number);
        source.setSourceStepId(parentId);
        return source;
    }

    private static List<String> ids(List<FlowRunStepResponse> steps) {
        return steps.stream().map(FlowRunStepResponse::stepId).toList();
    }
}
