package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.*;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

class FlowRunHttpReportScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"production", "manual"})
    void ordinaryReportsOutsideGenericActualGraphsKeepTheirExistingBehavior(String scope) {
        var runs = mock(FlowRunRepository.class);
        var steps = mock(FlowRunStepRepository.class);
        var attempts = mock(FlowRunStepAttemptRepository.class);
        var events = mock(FlowRunEventRepository.class);
        var recorder = mock(FlowRunEventRecorder.class);
        var workflow = mock(WorkflowProductionQuery.class);
        var access = mock(ProjectAccessService.class);
        var ids = mock(IdGenerator.class);
        var mapper = new ObjectMapper();
        var runService = mock(FlowRunService.class);
        var router = mock(FlowRunRouter.class);
        var sources = mock(FlowRunStepSourceRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var lifecycle = mock(FlowRunStepAttemptLifecycle.class);
        var service = new FlowRunStepService(runs, steps, attempts, events, recorder, workflow, access,
            ids, mapper, runService, router, sources, jdbc, lifecycle);
        FlowRun run = new FlowRun();
        run.setFlowRunId("run"); run.setProjectId("project"); run.setWorkflowId("workflow");
        run.setWorkflowRevisionId("revision"); run.setRunType("actual"); run.setStatus("running");
        run.setExecutionMode("production".equals(scope) ? "graph" : "manual");
        if ("production".equals(scope)) run.setProductionRunId("production");
        FlowRunStep step = new FlowRunStep();
        step.setStepId("step"); step.setFlowRunId("run"); step.setNodeId("node");
        step.setStatus("running"); step.setSequenceNo(1);
        FlowRunStepAttempt attempt = new FlowRunStepAttempt();
        attempt.setAttemptId("attempt"); attempt.setStepId("step"); attempt.setAttemptNo(1); attempt.setStatus("running");
        when(steps.findByStepIdAndFlowRunId("step", "run")).thenReturn(Optional.of(step));
        when(lifecycle.runningAttempt(step, 1)).thenReturn(attempt);
        when(workflow.findRevision("revision", "workflow")).thenReturn(Optional.of(
            new WorkflowProductionQuery.Revision("revision", "published", """
                {"schemaVersion":4,"processes":[{"processId":"node"}],"processIos":[],"connections":[],
                 "nodeBindings":[{"processId":"node","profileVersionId":"profile","operationId":"send"}]}
                """, 1)));

        var result = service.complete(run, "step", new FlowRunStepCompleteRequest(mapper.createObjectNode(), 1));

        assertEquals("completed", result.status());
        assertEquals("completed", attempt.getStatus());
        verify(steps).saveAndFlush(step);
        if ("manual".equals(scope)) verifyNoInteractions(workflow);
    }
}
