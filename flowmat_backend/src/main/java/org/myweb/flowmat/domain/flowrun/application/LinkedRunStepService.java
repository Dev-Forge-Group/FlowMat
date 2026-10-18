package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepResponse;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunStep;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunSteps;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The process steps of production-linked flow runs (docs/architecture/adr/ADR-011-production-steps.md). They move as in any
 * graph run; only the production run API drives them, so the flow run's own step API still refuses linked runs.
 */
@Service
@RequiredArgsConstructor
public class LinkedRunStepService implements LinkedRunSteps {
    static final String NO_STEPS =
        "This run has no process steps: it started before they were added, or its workflow revision cannot run as a graph.";

    private final FlowRunRepository runRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final FlowRunStepService stepService;
    private final WorkflowProductionQuery workflowQuery;
    private final ProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<LinkedRunStep> steps(String productionRunId) {
        FlowRun run = runRepository.findByProductionRunId(productionRunId).orElse(null);
        if (run == null || !"graph".equals(run.getExecutionMode())) {
            return List.of();
        }
        Nodes nodes = nodes(run);
        return stepService.list(run.getFlowRunId()).stream().map(step -> view(step, nodes)).toList();
    }

    @Override
    @Transactional
    public LinkedRunStep start(String productionRunId, String stepId) {
        FlowRun run = writable(productionRunId);
        return view(stepService.start(run, stepId), nodes(run));
    }

    @Override
    @Transactional
    public LinkedRunStep complete(String productionRunId, String stepId, JsonNode output) {
        FlowRun run = writable(productionRunId);
        return view(stepService.complete(run, stepId, new FlowRunStepCompleteRequest(output, null)), nodes(run));
    }

    @Override
    @Transactional
    public LinkedRunStep fail(String productionRunId, String stepId, String errorCode, String errorMessage) {
        FlowRun run = writable(productionRunId);
        return view(stepService.fail(run, stepId, new FlowRunStepFailRequest(errorCode, errorMessage, null)), nodes(run));
    }

    private FlowRun writable(String productionRunId) {
        FlowRun run = runRepository.findLockedByProductionRunId(productionRunId)
            .filter(found -> "graph".equals(found.getExecutionMode()))
            .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, NO_STEPS));
        accessService.requireProjectWriteAccess(run.getProjectId());
        if (!"running".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "failed".equals(run.getStatus())
                ? "A failed step stopped this run's process steps." : "This run's process steps have ended.");
        }
        return run;
    }

    /** The process names of the run's revision and its graph. */
    private record Nodes(Map<String, String> names, FlowRunGraph graph) {
    }

    private Nodes nodes(FlowRun run) {
        JsonNode snapshot = workflowQuery.findRevision(run.getWorkflowRevisionId(), run.getWorkflowId())
            .map(revision -> readJson(revision.snapshotJson()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Map<String, String> names = new HashMap<>();
        snapshot.path("processes").forEach(process ->
            names.put(process.path("processId").asText(), process.path("processName").asText(null)));
        return new Nodes(names, FlowRunGraph.from(snapshot));
    }

    private LinkedRunStep view(FlowRunStepResponse step, Nodes nodes) {
        // The latest attempt's time limit and late mark (docs/domain/flow-run.md F12).
        FlowRunStepAttempt attempt = attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.stepId()).orElse(null);
        return new LinkedRunStep(step.stepId(), step.nodeId(), nodes.names().get(step.nodeId()), step.sequenceNo(), step.status(),
            nodes.graph().gateway(step.nodeId()), step.sourceStepId(), step.sourceConnectionId(), step.scheduledAt(),
            step.startedAt(), step.endedAt(), step.errorCode(), step.errorMessage(), step.inputSnapshot(), step.outputSnapshot(),
            step.joinWaiting(), attempt == null ? null : attempt.getTimeoutAt(), attempt == null ? null : attempt.getOverdueAt());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow revision snapshot could not be read.", exception);
        }
    }
}
