package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.CancellableConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepFailRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpExecutionResponse;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpRequestPreviewResponse;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunHttpExecution;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunHttpExecutionRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, claimed HTTP execution. Every transaction ends before contacting the provider. */
@Service
public class FlowRunHttpExecutionService {
    private static final ObjectReader REQUEST_READER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxNumberLength(128).maxStringLength(1024).build())
        .build()).reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final FlowRunRepository runs;
    private final FlowRunStepRepository steps;
    private final FlowRunStepAttemptRepository attempts;
    private final FlowRunHttpExecutionRepository executions;
    private final ProjectAccessService access;
    private final WorkflowProductionQuery workflow;
    private final ConnectorProfileQuery profiles;
    private final ConnectorMappingQuery mapping;
    private final ConnectorHttpGateway gateway;
    private final FlowRunStepService stepService;
    private final IdGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final FlowRunHttpInFlightRequests inFlight;

    public FlowRunHttpExecutionService(FlowRunRepository runs, FlowRunStepRepository steps,
        FlowRunStepAttemptRepository attempts, FlowRunHttpExecutionRepository executions, ProjectAccessService access,
        WorkflowProductionQuery workflow, ConnectorProfileQuery profiles, ConnectorMappingQuery mapping,
        ConnectorHttpGateway gateway, FlowRunStepService stepService, IdGenerator ids, ObjectMapper mapper,
        PlatformTransactionManager transactionManager, FlowRunHttpInFlightRequests inFlight) {
        this.runs = runs; this.steps = steps; this.attempts = attempts; this.executions = executions;
        this.access = access; this.workflow = workflow; this.profiles = profiles; this.mapping = mapping;
        this.gateway = gateway; this.stepService = stepService; this.ids = ids; this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.inFlight = inFlight;
    }

    /** Reads the same frozen request as claim without starting a step or resolving credentials. */
    @Transactional(readOnly = true)
    public FlowRunHttpRequestPreviewResponse preview(String runId, String stepId) {
        FlowRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectOwnerAccess(run.getProjectId());
        requireHttpRun(run);
        FlowRunStep step = requireStep(runId, stepId);
        requireHttpStep(step);
        PreparedRequest prepared = prepare(run, step);
        ConnectorProfileOperation operation = prepared.operation();
        boolean hasBody = !"GET".equals(operation.method());
        return new FlowRunHttpRequestPreviewResponse(prepared.binding().profileVersionId(),
            prepared.binding().operationId(), operation.method(), operation.baseOrigin() + operation.relativePath(),
            hasBody, hasBody ? writeJson(prepared.request()) : null);
    }

    /** Authentication precedes reading the bounded caller body; claim rechecks authorization afterwards. */
    public FlowRunHttpExecutionResponse execute(String runId, String stepId, InputStream body) {
        requireNoCallerTransaction();
        transaction.executeWithoutResult(status -> { FlowRun run = lockedRun(runId); access.requireProjectOwnerAccess(run.getProjectId()); });
        UUID request;
        try {
            byte[] bytes = body.readNBytes(1025);
            if (bytes.length > 1024) throw badRequest();
            JsonNode json = REQUEST_READER.readTree(bytes);
            if (json == null || !json.isObject() || json.size() != 1 || !json.path("requestId").isTextual()) throw badRequest();
            request = UUID.fromString(json.path("requestId").textValue());
            if (!request.toString().equalsIgnoreCase(json.path("requestId").textValue())) throw badRequest();
        } catch (IOException | IllegalArgumentException failure) { throw badRequest(); }
        return execute(runId, stepId, request);
    }

    public FlowRunHttpExecutionResponse execute(String runId, String stepId, UUID requestId) {
        requireNoCallerTransaction();
        if (requestId == null) throw badRequest();
        Claim claim = transaction.execute(status -> claim(runId, stepId, requestId));
        if (!claim.send()) return response(claim.execution());
        ConnectorHttpOutcome outcome;
        // A second authorization/status check limits the cancel/disable window; an external side effect remains irreversible.
        boolean current;
        RuntimeException preflightRefusal = null;
        // Register before the locked recheck, so a stop cannot commit between the check and registration.
        try (var active = inFlight.track(runId, claim.execution().getExecutionId())) {
            try {
                current = Boolean.TRUE.equals(transaction.execute(status -> stillCurrent(claim.execution(), true)));
            } catch (RuntimeException refused) {
                // No gateway has been entered; a failed database recheck is known not to have sent anything.
                current = false;
                preflightRefusal = refused;
            }
            if (!current) {
                outcome = new ConnectorHttpOutcome("TRANSPORT_FAILED", null, null, "RUN_NO_LONGER_ACTIVE", false);
            } else {
                try {
                    ConnectorProfileOperation operation;
                    try {
                        operation = profiles.requireOperation(claim.projectId(), claim.execution().getProfileVersionId(), claim.execution().getOperationId());
                    } catch (RuntimeException refused) {
                        // Operation lookup is local. Only failures after entering the gateway can be uncertain.
                        operation = null;
                    }
                    outcome = operation == null
                        ? new ConnectorHttpOutcome("TRANSPORT_FAILED", null, null, "CONNECTOR_REFUSED", false)
                        : gateway instanceof CancellableConnectorHttpGateway cancellable
                            ? cancellable.execute(operation, claim.request(), active.cancellation())
                            : gateway.execute(operation, claim.request());
                    if (outcome == null) outcome = unknown();
                } catch (RuntimeException failed) {
                    // The provider may have committed before a client failure; never infer that a resend is safe.
                    outcome = unknown();
                }
            }
        }
        ConnectorHttpOutcome result = outcome;
        JsonNode mappedOutput = null;
        boolean mappingFailed = false;
        if ("SUCCEEDED".equals(result.status())) {
            try {
                mappedOutput = mapping.map(result.response() == null ? NullNode.getInstance() : result.response(), claim.responseMapping());
            } catch (RuntimeException invalidMapping) {
                // The provider may have committed. Retain safe evidence without retaining its unselected response.
                mappingFailed = true;
            }
        }
        JsonNode selected = mappedOutput;
        boolean rejectedMapping = mappingFailed;
        transaction.executeWithoutResult(status -> recordResult(claim.execution().getExecutionId(), result, selected, rejectedMapping));
        if (preflightRefusal != null) {
            transaction.executeWithoutResult(status -> {
                FlowRunHttpExecution execution = executions.findById(claim.execution().getExecutionId()).orElseThrow();
                execution.setApplyStatus("APPLY_FAILED"); executions.saveAndFlush(execution);
            });
            throw preflightRefusal;
        }
        if (mappingFailed) return transaction.execute(status -> {
            FlowRun run = lockedRun(runId);
            access.requireProjectOwnerAccess(run.getProjectId());
            return response(requireExecution(runId, stepId, claim.execution().getExecutionId()));
        });
        return applySafely(runId, stepId, claim.execution().getExecutionId());
    }

    public FlowRunHttpExecutionResponse retryApply(String runId, String stepId, String executionId) {
        requireNoCallerTransaction();
        transaction.executeWithoutResult(status -> {
            FlowRun run = lockedRun(runId); access.requireProjectOwnerAccess(run.getProjectId());
            FlowRunHttpExecution execution = requireExecution(runId, stepId, executionId);
            if (Set.of("PENDING", "OUTCOME_UNKNOWN").contains(execution.getTransportStatus()) || !"APPLY_FAILED".equals(execution.getApplyStatus())) {
                throw conflict("Only a resolved HTTP result with failed local application can be applied again.");
            }
            if ("SUCCEEDED".equals(execution.getTransportStatus()) && execution.getOutputJson() == null) {
                throw conflict("The response mapping failed; the unselected provider response was not retained and cannot be replayed.");
            }
        });
        return applySafely(runId, stepId, executionId);
    }

    public List<FlowRunHttpExecutionResponse> list(String runId, String stepId) {
        return transaction.execute(status -> {
            FlowRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            access.requireProjectReadAccess(run.getProjectId());
            requireStep(runId, stepId);
            return executions.findAllByStepIdOrderByStartedAtAscExecutionIdAsc(stepId).stream().map(this::response).toList();
        });
    }

    private Claim claim(String runId, String stepId, UUID requestId) {
        FlowRun run = lockedRun(runId);
        access.requireProjectOwnerAccess(run.getProjectId());
        requireStep(runId, stepId);
        var previous = executions.findByStepIdAndRequestId(stepId, requestId.toString());
        if (previous.isPresent()) return new Claim(previous.get(), run.getProjectId(), NullNode.getInstance(), NullNode.getInstance(), false);
        requireHttpRun(run);
        if (executions.hasUnresolvedTransmission(stepId)) {
            throw conflict("A pending, uncertain or unapplied HTTP transmission blocks another send for this step.");
        }
        FlowRunStep step = requireStep(runId, stepId);
        requireHttpStep(step);
        PreparedRequest prepared = prepare(run, step);
        FlowRunHttpBinding binding = prepared.binding();
        JsonNode request = prepared.request();
        if ("planned".equals(step.getStatus())) stepService.start(run, stepId);
        if (!"running".equals(step.getStatus())) throw conflict("Only a planned or running step can execute HTTP.");
        FlowRunStepAttempt attempt = attempts.findTopByStepIdOrderByAttemptNoDesc(stepId)
            .filter(value -> "running".equals(value.getStatus()))
            .orElseThrow(() -> conflict("Step has no current running attempt."));
        if (executions.findByAttemptId(attempt.getAttemptId()).isPresent()) {
            throw conflict("This attempt already has an HTTP transmission; reuse its requestId.");
        }
        FlowRunHttpExecution execution = new FlowRunHttpExecution();
        execution.setExecutionId(ids.generate()); execution.setClaimToken(ids.generate());
        execution.setFlowRunId(runId); execution.setStepId(stepId); execution.setAttemptId(attempt.getAttemptId());
        execution.setRequestId(requestId.toString()); execution.setProfileVersionId(binding.profileVersionId());
        execution.setOperationId(binding.operationId()); execution.setRequestHash(hash(writeJson(request)));
        execution.setTransportStatus("PENDING"); execution.setApplyStatus("PENDING");
        execution.setRequestedBy(access.requireCurrentUserId()); execution.setStartedAt(now());
        executions.saveAndFlush(execution);
        return new Claim(execution, run.getProjectId(), request, binding.responseMapping(), true);
    }

    private void recordResult(String executionId, ConnectorHttpOutcome outcome, JsonNode mappedOutput, boolean mappingFailed) {
        FlowRunHttpExecution execution = executions.findById(executionId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"PENDING".equals(execution.getTransportStatus())) return;
        Set<String> allowed = Set.of("SUCCEEDED", "HTTP_REJECTED", "RESPONSE_REJECTED", "TRANSPORT_FAILED", "OUTCOME_UNKNOWN");
        String status = outcome.status() != null && allowed.contains(outcome.status()) ? outcome.status() : "OUTCOME_UNKNOWN";
        if ("TRANSPORT_FAILED".equals(status) && outcome.mayHaveBeenSent()) status = "OUTCOME_UNKNOWN";
        execution.setTransportStatus(status);
        Integer httpStatus = outcome.httpStatus();
        execution.setHttpStatus(httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null);
        execution.setCompletedAt(now());
        execution.setErrorCode(safeError(status));
        if ("SUCCEEDED".equals(status)) {
            if (mappingFailed || mappedOutput == null) {
                execution.setApplyStatus("APPLY_FAILED");
                execution.setErrorCode("RESPONSE_MAPPING_FAILED");
            } else {
                execution.setOutputJson(writeJson(mappedOutput));
            }
        }
        executions.saveAndFlush(execution);
    }

    private FlowRunHttpExecutionResponse applySafely(String runId, String stepId, String executionId) {
        try {
            return transaction.execute(status -> apply(runId, stepId, executionId));
        } catch (RuntimeException localFailure) {
            // This commit is independent of the rolled back route/complete transaction and never retransmits.
            FlowRunHttpExecutionResponse result = transaction.execute(status -> {
                lockedRun(runId);
                FlowRunHttpExecution execution = requireExecution(runId, stepId, executionId);
                if (!"APPLIED".equals(execution.getApplyStatus()) && !"LATE".equals(execution.getApplyStatus())) {
                    execution.setApplyStatus("APPLY_FAILED"); execution.setErrorCode("LOCAL_APPLY_FAILED");
                    executions.saveAndFlush(execution);
                }
                return response(execution);
            });
            if (localFailure instanceof BusinessException business && Set.of(ErrorCode.FORBIDDEN, ErrorCode.UNAUTHORIZED, ErrorCode.NOT_FOUND).contains(business.getErrorCode())) {
                throw business;
            }
            return result;
        }
    }

    private FlowRunHttpExecutionResponse apply(String runId, String stepId, String executionId) {
        FlowRun run = lockedRun(runId);
        access.requireProjectOwnerAccess(run.getProjectId());
        FlowRunHttpExecution execution = requireExecution(runId, stepId, executionId);
        if (Set.of("APPLIED", "LATE").contains(execution.getApplyStatus())) return response(execution);
        if ("PENDING".equals(execution.getTransportStatus())) return response(execution);
        if (!stillCurrent(execution, false)) {
            execution.setApplyStatus("LATE"); executions.saveAndFlush(execution); return response(execution);
        }
        if ("OUTCOME_UNKNOWN".equals(execution.getTransportStatus())) return response(execution);
        FlowRunStep step = requireStep(runId, stepId);
        FlowRunStepAttempt attempt = attempts.findTopByStepIdOrderByAttemptNoDesc(stepId).orElseThrow();
        if ("SUCCEEDED".equals(execution.getTransportStatus())) {
            JsonNode snapshot = snapshot(run);
            FlowRunHttpBinding binding = FlowRunHttpBinding.read(snapshot, step.getNodeId());
            if (execution.getOutputJson() == null) return response(execution);
            JsonNode output = readJson(execution.getOutputJson());
            FlowRunPortContracts.requirePublishedOutput(snapshot, step.getNodeId(), binding.outputPortId(), output);
            execution.setApplyStatus("PENDING"); executions.saveAndFlush(execution);
            stepService.completeHttp(run, stepId, new FlowRunStepCompleteRequest(output, attempt.getAttemptNo()), execution.getClaimToken());
        } else {
            execution.setApplyStatus("PENDING"); executions.saveAndFlush(execution);
            stepService.failHttp(run, stepId, new FlowRunStepFailRequest("HTTP_EXECUTION_FAILED",
                "The HTTP execution did not produce an accepted response.", attempt.getAttemptNo()), execution.getClaimToken());
        }
        execution.setApplyStatus("APPLIED"); execution.setErrorCode(safeError(execution.getTransportStatus()));
        executions.saveAndFlush(execution);
        return response(execution);
    }

    private boolean stillCurrent(FlowRunHttpExecution execution, boolean authorize) {
        FlowRun run = lockedRun(execution.getFlowRunId());
        if (authorize) access.requireProjectOwnerAccess(run.getProjectId());
        FlowRunStep step = requireStep(execution.getFlowRunId(), execution.getStepId());
        FlowRunStepAttempt current = attempts.findTopByStepIdOrderByAttemptNoDesc(execution.getStepId()).orElse(null);
        return "running".equals(run.getStatus()) && "running".equals(step.getStatus()) && current != null
            && "running".equals(current.getStatus()) && execution.getAttemptId().equals(current.getAttemptId());
    }

    private PreparedRequest prepare(FlowRun run, FlowRunStep step) {
        FlowRunHttpBinding binding = FlowRunHttpBinding.read(snapshot(run), step.getNodeId());
        ConnectorProfileOperation operation = profiles.requireOperation(run.getProjectId(), binding.profileVersionId(), binding.operationId());
        JsonNode request;
        try {
            request = mapping.map(readJson(step.getInputSnapshot()), binding.requestMapping());
        } catch (RuntimeException invalid) {
            // Mapping and parser exceptions must not echo private input into a public error.
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestMapping could not produce a valid bounded HTTP request.");
        }
        if (request == null) request = NullNode.getInstance();
        if ("GET".equals(operation.method()) && !request.isNull()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestMapping for a GET operation must produce null; GET requests have no body.");
        }
        return new PreparedRequest(binding, operation, request);
    }

    private static void requireHttpRun(FlowRun run) {
        if (!"running".equals(run.getStatus()) || !"actual".equals(run.getRunType())
            || !"graph".equals(run.getExecutionMode()) || run.getProductionRunId() != null) {
            throw conflict("HTTP execution requires a running actual graph run without a production run.");
        }
    }

    private static void requireHttpStep(FlowRunStep step) {
        if (!"planned".equals(step.getStatus()) && !"running".equals(step.getStatus())) {
            throw conflict("Only a planned or running step can execute HTTP.");
        }
    }
    private FlowRun lockedRun(String runId) { return runs.findLockedByFlowRunId(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)); }
    private FlowRunStep requireStep(String runId, String stepId) { return steps.findByStepIdAndFlowRunId(stepId, runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)); }
    private FlowRunHttpExecution requireExecution(String runId, String stepId, String executionId) {
        return executions.findById(executionId).filter(e -> runId.equals(e.getFlowRunId()) && stepId.equals(e.getStepId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private JsonNode snapshot(FlowRun run) {
        return workflow.findRevision(run.getWorkflowRevisionId(), run.getWorkflowId())
            .map(revision -> readJson(revision.snapshotJson())).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private FlowRunHttpExecutionResponse response(FlowRunHttpExecution value) {
        return new FlowRunHttpExecutionResponse(value.getExecutionId(), value.getStepId(), value.getAttemptId(), value.getRequestId(),
            value.getTransportStatus(), value.getApplyStatus(), value.getHttpStatus(), value.getErrorCode(),
            value.getOutputJson() == null ? null : readJson(value.getOutputJson()), value.getStartedAt(), value.getCompletedAt());
    }
    private JsonNode readJson(String value) {
        try { return value == null ? NullNode.getInstance() : mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Stored HTTP execution JSON is invalid."); }
    }
    private String writeJson(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("HTTP execution JSON could not be serialized."); }
    }
    private static String safeError(String status) {
        return switch (status) {
            case "SUCCEEDED" -> null;
            case "HTTP_REJECTED" -> "HTTP_STATUS_REJECTED";
            case "RESPONSE_REJECTED" -> "HTTP_RESPONSE_REJECTED";
            case "TRANSPORT_FAILED" -> "HTTP_NOT_SENT";
            default -> "REMOTE_OUTCOME_UNKNOWN";
        };
    }
    private static ConnectorHttpOutcome unknown() { return new ConnectorHttpOutcome("OUTCOME_UNKNOWN", null, null, "REMOTE_OUTCOME_UNKNOWN", true); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
    private static BusinessException badRequest() { return new BusinessException(ErrorCode.BAD_REQUEST, "requestId must be one canonical UUID in a JSON object no larger than 1024 bytes."); }
    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("HTTP execution cannot run within a caller transaction.");
    }
    private record Claim(FlowRunHttpExecution execution, String projectId, JsonNode request, JsonNode responseMapping, boolean send) {
        @Override public String toString() { return "Claim[executionId=" + execution.getExecutionId() + ", send=" + send + "]"; }
    }
    private record PreparedRequest(FlowRunHttpBinding binding, ConnectorProfileOperation operation, JsonNode request) {
        @Override public String toString() { return "PreparedRequest[operationId=" + binding.operationId() + "]"; }
    }
}
