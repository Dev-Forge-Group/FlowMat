package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFinishRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunCancelRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStartRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunResponse;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowRunService {

    static final Set<String> RUN_TYPES = Set.of("actual", "simulation", "test", "dry_run");

    private final FlowRunRepository flowRunRepository;
    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final FlowRunEventRecorder eventRecorder;
    private final WorkflowProductionQuery workflowQuery;
    private final ProjectAccessService projectAccessService;
    private final WorkflowAccess workflowAccess;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    @Transactional
    public FlowRunResponse start(FlowRunStartRequest request) {
        return start(request, "manual");
    }

    @Transactional
    public FlowRunResponse startGraph(FlowRunStartRequest request) {
        return start(request, "graph");
    }

    private FlowRunResponse start(FlowRunStartRequest request, String executionMode) {
        // Retire holds this same lock, so a start observes the committed revision status.
        WorkflowAccessView workflow = workflowAccess.requireWriteAndLock(request.workflowId().trim());
        // Read through the workflow context's public query, not its repository (ADR-002).
        WorkflowProductionQuery.Revision revision = workflowQuery
            .findRevision(request.workflowRevisionId().trim(), workflow.workflowId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Workflow revision was not found for this workflow."));
        if (!"published".equals(revision.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Retired workflow revisions cannot start new runs.");
        }
        String runType = request.runType().trim().toLowerCase();
        if (!RUN_TYPES.contains(runType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown flow run type.");
        }
        JsonNode input = request.inputPayload() != null ? request.inputPayload() : objectMapper.createObjectNode();
        FlowRunGraph graph = null;
        if ("graph".equals(executionMode)) {
            if (!input.isObject()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Graph inputPayload must be an object.");
            }
            graph = FlowRunGraph.from(readJson(revision.snapshotJson()));
        }

        FlowRun run = new FlowRun();
        run.setFlowRunId(idGenerator.generate());
        run.setProjectId(workflow.projectId());
        run.setWorkflowId(workflow.workflowId());
        run.setWorkflowRevisionId(revision.workflowRevisionId());
        run.setRunType(runType);
        run.setExecutionMode(executionMode);
        run.setStatus("running");
        run.setInputPayload(writeJson(input));
        run.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        run.setRequestedBy(projectAccessService.requireCurrentUserId());
        flowRunRepository.saveAndFlush(run);
        eventRecorder.record(run.getFlowRunId(), null, "run_started",
            readJson(run.getInputPayload()), run.getRequestedBy());
        if (graph != null) {
            createRootSteps(run, graph, input, run.getRequestedBy());
        }
        return toResponse(run);
    }

    /**
     * One planned step for each root node of the graph as the run starts. A production-linked run gets them the same way
     * (docs/architecture/adr/ADR-011-production-steps.md decision 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createRootSteps(FlowRun run, FlowRunGraph graph, JsonNode input, String actorId) {
        int sequence = 0;
        for (String nodeId : graph.roots()) {
            FlowRunStep step = new FlowRunStep();
            step.setStepId(idGenerator.generate());
            step.setFlowRunId(run.getFlowRunId());
            step.setNodeId(nodeId);
            step.setStatus("planned");
            step.setSequenceNo(++sequence);
            step.setInputSnapshot(run.getInputPayload());
            stepRepository.saveAndFlush(step);
            eventRecorder.record(run.getFlowRunId(), step.getStepId(), "step_created", input, actorId);
        }
    }

    public List<FlowRunResponse> list(String workflowId) {
        workflowAccess.requireRead(workflowId);
        return flowRunRepository.findAllByWorkflowIdOrderByStartedAtDesc(workflowId).stream()
            .map(this::toResponse)
            .toList();
    }

    public FlowRunResponse get(String flowRunId) {
        FlowRun run = findRun(flowRunId);
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        return toResponse(run);
    }

    @Transactional
    public FlowRunResponse finish(String flowRunId, FlowRunFinishRequest request) {
        FlowRun run = lockedWritableGenericRun(flowRunId);
        if (stepRepository.existsByFlowRunIdAndStatusNotIn(flowRunId, Set.of("completed", "skipped"))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Every recorded step must complete or be skipped before the flow run can finish.");
        }
        run.setStatus("finished");
        run.setOutputPayload(request != null && request.outputPayload() != null
            ? writeJson(request.outputPayload()) : null);
        run.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
        flowRunRepository.save(run);
        eventRecorder.record(run.getFlowRunId(), null, "run_finished",
            readJson(run.getOutputPayload()), projectAccessService.requireCurrentUserId());
        return toResponse(run);
    }

    @Transactional
    public FlowRunResponse cancel(String flowRunId, FlowRunCancelRequest request) {
        String reason = request.reason().trim();
        JsonNode payload = objectMapper.createObjectNode().put("reason", reason);
        return stop(flowRunId, "cancelled", "RUN_CANCELLED", reason, payload);
    }

    @Transactional
    public FlowRunResponse fail(String flowRunId, FlowRunFailRequest request) {
        String errorCode = request.errorCode().trim();
        JsonNode payload = objectMapper.createObjectNode()
            .put("errorCode", errorCode).put("errorMessage", request.errorMessage());
        return stop(flowRunId, "failed", errorCode, request.errorMessage(), payload);
    }

    /**
     * Fails a running generic run whose row the caller holds locked, for an actor it has authorized; a null actor is the
     * system, after a timeout (docs/domain/flow-run-execution-policy.md EP7).
     */
    public FlowRunResponse failLocked(FlowRun run, String errorCode, String errorMessage, String actorId) {
        JsonNode payload = objectMapper.createObjectNode().put("errorCode", errorCode).put("errorMessage", errorMessage);
        return stop(run, "failed", errorCode, errorMessage, payload, actorId);
    }

    private FlowRunResponse stop(String flowRunId, String terminalStatus, String errorCode,
        String errorMessage, JsonNode payload) {
        FlowRun run = lockedWritableGenericRun(flowRunId);
        return stop(run, terminalStatus, errorCode, errorMessage, payload, projectAccessService.requireCurrentUserId());
    }

    private FlowRunResponse stop(FlowRun run, String terminalStatus, String errorCode, String errorMessage, JsonNode payload,
        String actor) {
        String flowRunId = run.getFlowRunId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (FlowRunStep step : stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(flowRunId)) {
            if (!"planned".equals(step.getStatus()) && !"running".equals(step.getStatus())) {
                continue;
            }
            boolean wasRunning = "running".equals(step.getStatus());
            String stepStatus = "failed".equals(terminalStatus) && wasRunning ? "failed" : "cancelled";
            String stepErrorCode = "failed".equals(terminalStatus) && wasRunning
                ? errorCode : "RUN_" + terminalStatus.toUpperCase();
            if (wasRunning) {
                FlowRunStepAttempt attempt = attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.getStepId())
                    .orElseThrow(() -> new IllegalStateException("Running step has no attempt."));
                if (!"running".equals(attempt.getStatus())) {
                    throw new IllegalStateException("Running step's latest attempt is not running.");
                }
                attempt.setStatus(stepStatus);
                attempt.setEndedAt(now);
                attempt.setErrorCode(stepErrorCode);
                attempt.setErrorMessage(errorMessage);
                attemptRepository.save(attempt);
            }
            step.setStatus(stepStatus);
            step.setEndedAt(now);
            step.setErrorCode(stepErrorCode);
            step.setErrorMessage(errorMessage);
            stepRepository.save(step);
            eventRecorder.record(flowRunId, step.getStepId(), "step_" + stepStatus, payload, actor);
        }
        run.setStatus(terminalStatus);
        run.setEndedAt(now);
        flowRunRepository.save(run);
        eventRecorder.record(flowRunId, null, "run_" + terminalStatus, payload, actor);
        return toResponse(run);
    }

    private FlowRun lockedWritableGenericRun(String flowRunId) {
        FlowRun run = flowRunRepository.findLockedByFlowRunId(flowRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        if (run.getProductionRunId() != null) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Linked production runs must be managed through the production run API.");
        }
        if (!"running".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Flow run has already ended.");
        }
        return run;
    }

    private FlowRun findRun(String flowRunId) {
        return flowRunRepository.findById(flowRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private FlowRunResponse toResponse(FlowRun run) {
        return new FlowRunResponse(
            run.getFlowRunId(), run.getProjectId(), run.getWorkflowId(), run.getWorkflowRevisionId(),
            run.getProductionRunId(), run.getRunType(), run.getExecutionMode(), run.getStatus(),
            readJson(run.getInputPayload()), readJson(run.getOutputPayload()),
            run.getStartedAt(), run.getEndedAt(), run.getRequestedBy()
        );
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Flow run payload could not be serialized.", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored flow run payload is invalid.", exception);
        }
    }
}
