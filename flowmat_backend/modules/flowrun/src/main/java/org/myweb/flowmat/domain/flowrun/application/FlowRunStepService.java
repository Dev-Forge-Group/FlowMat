package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCreateRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepScheduleRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunEventResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunRoutePreviewResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepAttemptResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepLineageResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunStepResponse;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepSourceRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowRunStepService {
    private static final String JOIN_IS_PASSED = "A Join step waits for its branches; the server passes it on (ADR-007).";

    private final FlowRunRepository runRepository;
    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final FlowRunEventRepository eventRepository;
    private final FlowRunEventRecorder eventRecorder;
    private final WorkflowProductionQuery workflowQuery;
    private final ProjectAccess accessService;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final FlowRunService runService;
    private final FlowRunRouter router;
    private final FlowRunStepSourceRepository sourceRepository;
    private final JdbcTemplate jdbc;
    private final FlowRunStepAttemptLifecycle attemptLifecycle;

    @Transactional
    public FlowRunStepResponse create(String runId, FlowRunStepCreateRequest request) {
        FlowRun run = writableRun(runId);
        if ("graph".equals(run.getExecutionMode())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Graph run steps are created from the published revision.");
        }
        String nodeId = request.nodeId().trim();
        JsonNode processes = snapshot(run).path("processes");
        boolean belongsToRevision = processes.isArray() &&
            java.util.stream.StreamSupport.stream(processes.spliterator(), false)
                .anyMatch(node -> nodeId.equals(node.path("processId").asText()));
        if (!belongsToRevision) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Node is not in the run's published revision.");
        }
        long nextSequence = stepRepository.countByFlowRunId(runId) + 1;
        if (nextSequence > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.CONFLICT, "Flow run has too many steps.");
        }
        FlowRunStep step = new FlowRunStep();
        step.setStepId(idGenerator.generate());
        step.setFlowRunId(runId);
        step.setNodeId(nodeId);
        step.setStatus("planned");
        step.setSequenceNo((int) nextSequence);
        step.setScheduledAt(parseScheduledAt(request.scheduledAt()));
        step.setInputSnapshot(writeJson(request.inputSnapshot() == null
            ? objectMapper.createObjectNode() : request.inputSnapshot()));
        stepRepository.saveAndFlush(step);
        appendEvent(runId, step.getStepId(), "step_created", readJson(step.getInputSnapshot()));
        return response(step);
    }

    /**
     * Opens the next attempt. In a graph run a step waiting to retry starts no earlier than its retryAt, and the node's
     * concurrency limit is checked under the revision+node lock (docs/domain/flow-run-execution-policy.md EP5-EP6).
     */
    @Transactional
    public FlowRunStepResponse start(String runId, String stepId) {
        // The parent row serializes all step transitions and sequence allocation.
        return start(writableRun(runId), stepId);
    }

    /**
     * As {@link #start(String, String)} on a run the caller has locked and may change, such as a production-linked one
     * (docs/architecture/adr/ADR-011-production-steps.md decision 2).
     */
    @Transactional
    public FlowRunStepResponse start(FlowRun run, String stepId) {
        String runId = run.getFlowRunId();
        FlowRunStep step = requireStep(runId, stepId);
        if (!"planned".equals(step.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a planned step can start.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // A production step held for the equipment's next shift may go early once its equipment works (ADR-015 decision 6).
        if (step.getScheduledAt() != null && step.getScheduledAt().isAfter(now) && !router.mayStartEarly(run, step, now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Step cannot start before its scheduledAt.");
        }
        String actor = accessService.requireCurrentUserId();
        if (!"graph".equals(run.getExecutionMode())) {
            attemptLifecycle.beginAttempt(step, "step_started", null, actor);
            return response(step);
        }
        OffsetDateTime retryAt = pendingRetryAt(step);
        if (retryAt != null && retryAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Step cannot start before its retryAt.");
        }
        FlowRunGraph graph = graph(run);
        if (graph.join(step.getNodeId())) {
            throw new BusinessException(ErrorCode.CONFLICT, JOIN_IS_PASSED);
        }
        FlowRunGraph.NodePolicy policy = graph.policy(step.getNodeId());
        Integer limit = policy.concurrencyLimit();
        if (limit != null) {
            long running = runningOnNode(run, step.getNodeId());
            if (running >= limit) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "Node " + step.getNodeId() + " already has " + running + " running steps (limit " + limit + ").");
            }
        }
        attemptLifecycle.beginAttempt(step, "step_started", policy.timeoutSeconds(), actor);
        return response(step);
    }

    @Transactional
    public FlowRunStepResponse schedule(String runId, String stepId, FlowRunStepScheduleRequest request) {
        FlowRun run = writableRun(runId); // The parent row serializes all step transitions and sequence allocation.
        FlowRunStep step = requireStep(runId, stepId);
        if ("graph".equals(run.getExecutionMode()) && graph(run).join(step.getNodeId())) {
            throw new BusinessException(ErrorCode.CONFLICT, JOIN_IS_PASSED);
        }
        if (!"planned".equals(step.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a planned step can be rescheduled.");
        }
        OffsetDateTime scheduledAt = parseScheduledAt(request.scheduledAt());
        // A step waiting to retry keeps its delay (EP5).
        OffsetDateTime retryAt = pendingRetryAt(step);
        if (retryAt != null && (scheduledAt == null || scheduledAt.isBefore(retryAt))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Step waits to retry until " + retryAt + "; schedule it at or after then.");
        }
        // A step after a connection's delay keeps it (docs/architecture/adr/ADR-010-connection-delay.md decision 5).
        OffsetDateTime readyAt = inputReadyAt(run, step);
        if (readyAt != null && (scheduledAt == null || scheduledAt.isBefore(readyAt))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Step's input is ready at " + readyAt + "; schedule it at or after then.");
        }
        step.setScheduledAt(scheduledAt);
        stepRepository.saveAndFlush(step);
        appendEvent(runId, stepId, "step_scheduled", objectMapper.createObjectNode()
            .put("scheduledAt", scheduledAt == null ? null : scheduledAt.toString()));
        return response(step);
    }

    @Transactional
    public FlowRunStepResponse retry(String runId, String stepId) {
        FlowRun run = writableRun(runId);
        if ("graph".equals(run.getExecutionMode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Graph run retries follow the connection policy.");
        }
        FlowRunStep step = requireStep(runId, stepId);
        if (!"failed".equals(step.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a failed step can be retried.");
        }
        attemptLifecycle.beginAttempt(step, "step_retried", null, accessService.requireCurrentUserId());
        return response(step);
    }

    @Transactional
    public FlowRunStepResponse complete(String runId, String stepId, FlowRunStepCompleteRequest request) {
        return complete(writableRun(runId), stepId, request);
    }

    /** As {@link #complete(String, String, FlowRunStepCompleteRequest)} on a run the caller has locked and may change (ADR-011). */
    @Transactional
    public FlowRunStepResponse complete(FlowRun run, String stepId, FlowRunStepCompleteRequest request) {
        return completeReport(run, stepId, request, null);
    }

    /** Internal claimed executor path; callers cannot supply this token through the public step API. */
    FlowRunStepResponse completeHttp(FlowRun run, String stepId, FlowRunStepCompleteRequest request, String claimToken) {
        return completeReport(run, stepId, request, claimToken);
    }

    private FlowRunStepResponse completeReport(FlowRun run, String stepId, FlowRunStepCompleteRequest request, String claimToken) {
        String runId = run.getFlowRunId();
        FlowRunStep step = requireStep(runId, stepId);
        FlowRunStepAttempt attempt = attemptLifecycle.runningAttempt(step, request == null ? null : request.attemptNo());
        requireReportOwner(run, step, attempt.getAttemptId(), claimToken);
        JsonNode output = request != null && request.outputSnapshot() != null
            ? request.outputSnapshot() : objectMapper.createObjectNode();
        FlowRunGraph graph = "graph".equals(run.getExecutionMode()) ? graph(run) : null;
        List<FlowRunGraph.Decision> decisions = graph == null ? List.of() : graph.preview(step.getNodeId(), output);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        attempt.setStatus("completed");
        attempt.setEndedAt(now);
        step.setStatus("completed");
        step.setEndedAt(now);
        step.setOutputSnapshot(writeJson(output));
        attemptRepository.save(attempt);
        stepRepository.saveAndFlush(step);
        appendEvent(runId, stepId, "step_completed", readJson(step.getOutputSnapshot()));
        if (graph != null) {
            // A gateway on the way may end the run (ADR-006 decision 3); Joins nothing more can reach go on (ADR-007).
            FlowRunRouter.Failure failure = router.follow(runId, graph, stepId, decisions, output,
                accessService.requireCurrentUserId(), true);
            if (failure == null) {
                failure = router.settle(runId, graph, true);
            }
            if (failure != null) {
                runService.failLocked(run, failure.code(), failure.message(), null);
            }
        }
        return response(step);
    }

    public List<FlowRunRoutePreviewResponse> preview(String runId, String stepId,
        FlowRunStepCompleteRequest request) {
        FlowRun run = readableRun(runId);
        accessService.requireProjectWriteAccess(run.getProjectId());
        if (!"running".equals(run.getStatus()) || run.getProductionRunId() != null
            || !"graph".equals(run.getExecutionMode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a running graph step can be previewed.");
        }
        FlowRunStep step = requireStep(runId, stepId);
        attemptLifecycle.runningAttempt(step, request == null ? null : request.attemptNo());
        JsonNode output = request != null && request.outputSnapshot() != null
            ? request.outputSnapshot() : objectMapper.createObjectNode();
        return graph(run).preview(step.getNodeId(), output).stream()
            .map(decision -> new FlowRunRoutePreviewResponse(decision.connectionId(),
                decision.targetNodeId(), decision.willRoute()))
            .toList();
    }

    @Transactional
    public FlowRunStepResponse fail(String runId, String stepId, FlowRunStepFailRequest request) {
        return fail(writableRun(runId), stepId, request);
    }

    /** As {@link #fail(String, String, FlowRunStepFailRequest)} on a run the caller has locked and may change (ADR-011). */
    @Transactional
    public FlowRunStepResponse fail(FlowRun run, String stepId, FlowRunStepFailRequest request) {
        return failReport(run, stepId, request, null);
    }

    /** Internal claimed executor path, with the exact durable attempt claim checked below. */
    FlowRunStepResponse failHttp(FlowRun run, String stepId, FlowRunStepFailRequest request, String claimToken) {
        return failReport(run, stepId, request, claimToken);
    }

    private FlowRunStepResponse failReport(FlowRun run, String stepId, FlowRunStepFailRequest request, String claimToken) {
        String runId = run.getFlowRunId();
        FlowRunStep step = requireStep(runId, stepId);
        FlowRunStepAttempt attempt = attemptLifecycle.runningAttempt(step, request.attemptNo());
        requireReportOwner(run, step, attempt.getAttemptId(), claimToken);
        recordFailure(run, step, attempt, request.errorCode().trim(), request.errorMessage(),
            accessService.requireCurrentUserId());
        return response(step);
    }

    /**
     * Fails a running graph attempt whose node time limit passed, as the system (EP7). A production-linked attempt is only
     * marked late, since a person works it (docs/domain/flow-run.md F12). Checked again under the run lock, so overlapping
     * sweeps record it once; false when there was nothing to do.
     */
    @Transactional
    public boolean timeOut(String attemptId) {
        String runId = attemptRepository.findFlowRunIdByAttemptId(attemptId).orElse(null);
        if (runId == null) return false;
        FlowRun run = runRepository.findLockedByFlowRunId(runId).orElse(null);
        if (run == null || !"running".equals(run.getStatus()) || !"graph".equals(run.getExecutionMode())) {
            return false;
        }
        // Loaded only after the lock, so a report or another sweep that ended it is seen.
        FlowRunStepAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (attempt == null || !"running".equals(attempt.getStatus()) || attempt.getTimeoutAt() == null
            || attempt.getTimeoutAt().isAfter(now) || attempt.getOverdueAt() != null) {
            return false;
        }
        FlowRunStep step = requireStep(runId, attempt.getStepId());
        if (!"running".equals(step.getStatus())) return false;
        if (run.getProductionRunId() != null) {
            attempt.setOverdueAt(now);
            attemptRepository.save(attempt);
            eventRecorder.record(runId, step.getStepId(), "step_overdue", objectMapper.createObjectNode()
                .put("attemptNo", attempt.getAttemptNo()).put("timeoutAt", attempt.getTimeoutAt().toString()), null);
            return true;
        }
        long seconds = Duration.between(attempt.getStartedAt(), attempt.getTimeoutAt()).getSeconds();
        eventRecorder.record(runId, step.getStepId(), "step_timed_out", objectMapper.createObjectNode()
            .put("attemptNo", attempt.getAttemptNo()).put("timeoutAt", attempt.getTimeoutAt().toString()), null);
        recordFailure(run, step, attempt, "TIMEOUT",
            "Attempt " + attempt.getAttemptNo() + " ran longer than " + seconds + " seconds.", null);
        return true;
    }

    public List<FlowRunStepResponse> list(String runId) {
        FlowRun run = readableRun(runId);
        List<FlowRunStep> steps = stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(runId);
        Map<String, Integer> waiting = joinWaiting(run, steps);
        return steps.stream().map(step -> response(step, waiting)).toList();
    }

    /** For each Join step, how many of its connections can still bring something; 0 once passed (ADR-007 decision 11). */
    private Map<String, Integer> joinWaiting(FlowRun run, List<FlowRunStep> steps) {
        Set<String> joins = sourceRepository.findAllByFlowRunIdOrderByStepIdAscArrivalNoAsc(run.getFlowRunId()).stream()
            .map(FlowRunStepSource::getStepId)
            .collect(Collectors.toSet());
        if (joins.isEmpty()) {
            return Map.of();
        }
        FlowRunGraph graph = graph(run);
        Map<String, Integer> waiting = new HashMap<>();
        for (FlowRunStep step : steps) {
            if (joins.contains(step.getStepId())) {
                waiting.put(step.getStepId(), "planned".equals(step.getStatus())
                    ? graph.stillComing(step.getNodeId(), FlowRunRouter.liveNodes(steps, step)) : 0);
            }
        }
        return waiting;
    }

    public FlowRunStepLineageResponse lineage(String runId, String stepId) {
        readableRun(runId);
        List<FlowRunStep> steps = stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(runId);
        var lineage = new FlowRunStepLineageResolver(stepId, steps,
            sourceRepository.findAllByFlowRunIdOrderByStepIdAscArrivalNoAsc(runId));
        List<FlowRunStepResponse> ancestors = lineage.ancestors().stream().map(this::response).toList();
        List<FlowRunStepResponse> descendants = lineage.descendants().stream().map(this::response).toList();
        return new FlowRunStepLineageResponse(response(lineage.selected()), ancestors, descendants);
    }

    public List<FlowRunStepAttemptResponse> attempts(String runId, String stepId) {
        readableRun(runId);
        requireStep(runId, stepId);
        return attemptRepository.findAllByStepIdOrderByAttemptNoAsc(stepId).stream()
            .map(attempt -> new FlowRunStepAttemptResponse(
                attempt.getAttemptId(), attempt.getStepId(), attempt.getAttemptNo(), attempt.getStatus(),
                attempt.getStartedAt(), attempt.getEndedAt(), attempt.getRetryAt(),
                attempt.getErrorCode(), attempt.getErrorMessage(), attempt.getTimeoutAt(), attempt.getOverdueAt()))
            .toList();
    }

    public List<FlowRunEventResponse> events(String runId) {
        readableRun(runId);
        return eventRepository.findAllByFlowRunIdOrderByOccurredAtAscEventIdAsc(runId).stream()
            .map(event -> new FlowRunEventResponse(event.getEventId(), event.getFlowRunId(),
                event.getStepId(), event.getEventType(), readJson(event.getPayloadJson()),
                event.getRequestId(), event.getOccurredAt(), event.getActorType(), event.getActorId()))
            .toList();
    }

    /**
     * Ends the running attempt as failed and follows the connection's failure policy with the node's retry policy
     * (EP4, EP6). {@code actorId} null is the system, for a timeout (EP7).
     */
    private void recordFailure(FlowRun run, FlowRunStep step, FlowRunStepAttempt attempt, String code, String message,
        String actorId) {
        // Stored to the microsecond, so the retryAt answered here is the one saved.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        attempt.setStatus("failed");
        attempt.setEndedAt(now);
        attempt.setErrorCode(code);
        attempt.setErrorMessage(message);
        step.setStatus("failed");
        step.setEndedAt(now);
        step.setErrorCode(code);
        step.setErrorMessage(message);
        attemptRepository.save(attempt);
        stepRepository.save(step);
        JsonNode payload = objectMapper.createObjectNode().put("errorCode", code).put("errorMessage", message);
        eventRecorder.record(run.getFlowRunId(), step.getStepId(), "step_failed", payload, actorId);
        if (!"graph".equals(run.getExecutionMode())) {
            return;
        }
        FlowRunGraph graph = graph(run);
        String policy = graph.failurePolicy(step.getSourceConnectionId());
        FlowRunGraph.NodePolicy node = graph.policy(step.getNodeId());
        if ("skip".equals(policy)) {
            step.setStatus("skipped");
            stepRepository.save(step);
            eventRecorder.record(run.getFlowRunId(), step.getStepId(), "step_skipped", payload, actorId);
            stepRepository.flush();
            // A skipped branch brings nothing more, so a Join waiting on it may go on; no report is left to reject it
            // when its output cannot (ADR-007 decision 9).
            FlowRunRouter.Failure failure = router.settle(run.getFlowRunId(), graph, false);
            if (failure != null) {
                runService.failLocked(run, failure.code(), failure.message(), null);
            }
        } else if ("retry".equals(policy) && attempt.getAttemptNo() <= node.retryLimit()) {
            long delay = node.delayBeforeRetry(attempt.getAttemptNo());
            if (delay > 0) {
                waitToRetry(step, attempt, now.plusSeconds(delay), null, actorId);
            } else if (node.concurrencyLimit() != null && runningOnNode(run, step.getNodeId()) >= node.concurrencyLimit()) {
                waitToRetry(step, attempt, null, "concurrency_limit", actorId);
            } else {
                attemptLifecycle.beginAttempt(step, "step_retried", node.timeoutSeconds(), actorId);
            }
        } else {
            runService.failLocked(run, code, message, actorId);
        }
    }

    /** Back to planned until {@code retryAt}, or until an executor starts it when the limit blocked the retry (EP4, EP6). */
    private void waitToRetry(FlowRunStep step, FlowRunStepAttempt attempt, OffsetDateTime retryAt, String reason,
        String actorId) {
        attempt.setRetryAt(retryAt);
        attemptRepository.save(attempt);
        step.setStatus("planned");
        step.setScheduledAt(retryAt);
        step.setEndedAt(null);
        stepRepository.save(step);
        ObjectNode payload = objectMapper.createObjectNode().put("attemptNo", attempt.getAttemptNo())
            .put("retryAt", retryAt == null ? null : retryAt.toString());
        if (reason != null) payload.put("reason", reason);
        eventRecorder.record(step.getFlowRunId(), step.getStepId(), "step_retry_scheduled", payload, actorId);
    }

    /** The retryAt of the failed attempt a planned step waits on, if any (EP5). */
    private OffsetDateTime pendingRetryAt(FlowRunStep step) {
        return attemptRepository.findTopByStepIdOrderByAttemptNoDesc(step.getStepId())
            .filter(attempt -> "failed".equals(attempt.getStatus()))
            .map(FlowRunStepAttempt::getRetryAt)
            .orElse(null);
    }

    /** Running steps of the node in the revision's open graph runs, counted under the revision+node lock (EP6). */
    private long runningOnNode(FlowRun run, String nodeId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
            "flow-run-node|" + run.getWorkflowRevisionId() + "|" + nodeId);
        return stepRepository.countRunningGraphSteps(run.getWorkflowRevisionId(), nodeId);
    }

    private void requireReportOwner(FlowRun run, FlowRunStep step, String attemptId, String claimToken) {
        if (claimToken == null) {
            if ("actual".equals(run.getRunType()) && "graph".equals(run.getExecutionMode()) && run.getProductionRunId() == null) {
                JsonNode published = snapshot(run);
                if (published.path("schemaVersion").asInt() == 4 && published.path("nodeBindings").isArray()) {
                    for (JsonNode binding : published.path("nodeBindings")) {
                        if (step.getNodeId().equals(binding.path("processId").asText())) {
                            throw new BusinessException(ErrorCode.CONFLICT, "An actual HTTP-bound step must report through HTTP execution evidence.");
                        }
                    }
                }
            }
            Boolean claimed = jdbc.queryForObject("select exists(select 1 from flow_run_http_execution where attempt_id = ?"
                + " or (step_id = ? and (transport_status in ('PENDING','OUTCOME_UNKNOWN')"
                + " or (transport_status in ('SUCCEEDED','HTTP_REJECTED','RESPONSE_REJECTED') and apply_status <> 'APPLIED'))))",
                Boolean.class, attemptId, step.getStepId());
            if (Boolean.TRUE.equals(claimed)) {
                throw new BusinessException(ErrorCode.CONFLICT, "This attempt is owned by its HTTP execution; use HTTP execution evidence and local apply.");
            }
        } else {
            Boolean owned = jdbc.queryForObject("select exists(select 1 from flow_run_http_execution"
                + " where attempt_id = ? and claim_token = ? and apply_status = 'PENDING'"
                + " and transport_status in ('SUCCEEDED','HTTP_REJECTED','RESPONSE_REJECTED','TRANSPORT_FAILED'))",
                Boolean.class, attemptId, claimToken);
            if (!Boolean.TRUE.equals(owned)) throw new BusinessException(ErrorCode.CONFLICT, "HTTP execution claim is no longer current.");
        }
    }

    private FlowRun writableRun(String runId) {
        FlowRun run = runRepository.findLockedByFlowRunId(runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessService.requireProjectWriteAccess(run.getProjectId());
        if (!"running".equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Flow run has already ended.");
        }
        if (run.getProductionRunId() != null) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Production-linked steps must be recorded through the production run API.");
        }
        return run;
    }

    private FlowRun readableRun(String runId) {
        FlowRun run = runRepository.findById(runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessService.requireProjectReadAccess(run.getProjectId());
        return run;
    }

    /** The run's published revision, read through the workflow context's public query (ADR-002, EP11). */
    private JsonNode snapshot(FlowRun run) {
        return workflowQuery.findRevision(run.getWorkflowRevisionId(), run.getWorkflowId())
            .map(revision -> readJson(revision.snapshotJson()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private FlowRunGraph graph(FlowRun run) {
        return FlowRunGraph.from(snapshot(run));
    }

    private FlowRunStep requireStep(String runId, String stepId) {
        return stepRepository.findByStepIdAndFlowRunId(stepId, runId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void appendEvent(String runId, String stepId, String type, JsonNode payload) {
        eventRecorder.record(runId, stepId, type, payload, accessService.requireCurrentUserId());
    }

    private FlowRunStepResponse response(FlowRunStep step) {
        return response(step, Map.of());
    }

    private FlowRunStepResponse response(FlowRunStep step, Map<String, Integer> joinWaiting) {
        return new FlowRunStepResponse(step.getStepId(), step.getFlowRunId(), step.getNodeId(),
            step.getSourceConnectionId(), step.getSourceStepId(), step.getStatus(),
            step.getSequenceNo(), step.getScheduledAt(), step.getStartedAt(),
            step.getEndedAt(), readJson(step.getInputSnapshot()), readJson(step.getOutputSnapshot()),
            step.getErrorCode(), step.getErrorMessage(), joinWaiting.get(step.getStepId()));
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Flow run step JSON could not be serialized.", exception);
        }
    }

    /** When the step's input is ready after its connection's delay (ADR-010), or null when no delay applies. */
    private OffsetDateTime inputReadyAt(FlowRun run, FlowRunStep step) {
        if (!"graph".equals(run.getExecutionMode()) || step.getSourceStepId() == null) {
            return null;
        }
        FlowRunGraph graph = graph(run);
        return stepRepository.findById(step.getSourceStepId())
            .map(source -> router.readyAt(graph, source, step.getSourceConnectionId()))
            .orElse(null);
    }

    private OffsetDateTime parseScheduledAt(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "scheduledAt must be an ISO-8601 date-time with an offset.");
        }
    }

    private JsonNode readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            // Decimals stay exact, as conditions and totals compare them.
            return objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored flow run step JSON is invalid.", exception);
        }
    }
}
