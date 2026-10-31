package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.connector.application.publicapi.*;
import org.myweb.flowmat.domain.flowrun.domain.entity.*;
import org.myweb.flowmat.domain.flowrun.repository.*;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.*;

class FlowRunHttpExecutionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FlowRunRepository runs = mock(FlowRunRepository.class);
    private final FlowRunStepRepository steps = mock(FlowRunStepRepository.class);
    private final FlowRunStepAttemptRepository attempts = mock(FlowRunStepAttemptRepository.class);
    private final FlowRunHttpExecutionRepository executions = mock(FlowRunHttpExecutionRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final WorkflowProductionQuery workflow = mock(WorkflowProductionQuery.class);
    private final ConnectorProfileQuery profiles = mock(ConnectorProfileQuery.class);
    private final ConnectorMappingQuery mapping = mock(ConnectorMappingQuery.class);
    private final ConnectorHttpGateway gateway = mock(ConnectorHttpGateway.class);
    private final FlowRunStepService stepService = mock(FlowRunStepService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final AtomicReference<FlowRunHttpExecution> saved = new AtomicReference<>();
    private FlowRunHttpExecutionService service;
    private FlowRun run;
    private FlowRunStep step;
    private FlowRunStepAttempt attempt;
    private JsonNode output;

    @BeforeEach void setup() throws Exception {
        service = new FlowRunHttpExecutionService(runs, steps, attempts, executions, access, workflow, profiles,
            mapping, gateway, stepService, ids, mapper, new TestTransactions(), new FlowRunHttpInFlightRequests());
        run = new FlowRun(); run.setFlowRunId("run"); run.setProjectId("project"); run.setWorkflowId("workflow");
        run.setWorkflowRevisionId("revision"); run.setStatus("running"); run.setRunType("actual"); run.setExecutionMode("graph");
        step = new FlowRunStep(); step.setStepId("step"); step.setFlowRunId("run"); step.setNodeId("node");
        step.setStatus("running"); step.setInputSnapshot("{\"quantity\":1}");
        attempt = new FlowRunStepAttempt(); attempt.setAttemptId("attempt"); attempt.setAttemptNo(1);
        attempt.setStepId("step"); attempt.setStatus("running");
        String snapshot = """
            {"schemaVersion":4,"processes":[{"processId":"node"}],"connections":[],
             "processIos":[{"processIoId":"out","processId":"node","direction":"output"}],
             "nodeBindings":[{"processId":"node","profileVersionId":"pv","operationId":"send",
              "outputPortId":"out","requestMapping":{"from":""},"responseMapping":{"from":""}}]}
            """;
        when(runs.findLockedByFlowRunId("run")).thenAnswer(i -> Optional.of(run));
        when(runs.findById("run")).thenAnswer(i -> Optional.of(run));
        when(steps.findByStepIdAndFlowRunId("step", "run")).thenReturn(Optional.of(step));
        when(attempts.findTopByStepIdOrderByAttemptNoDesc("step")).thenReturn(Optional.of(attempt));
        when(workflow.findRevision("revision", "workflow")).thenReturn(Optional.of(new WorkflowProductionQuery.Revision("revision", "published", snapshot, 1)));
        when(access.requireCurrentUserId()).thenReturn("owner");
        when(ids.generate()).thenReturn("execution", "claim");
        var operation = new ConnectorProfileOperation("project", "profile", "pv", 1, "https://example.test", null,
            "send", "POST", "/send", Set.of(200));
        when(profiles.requireOperation("project", "pv", "send")).thenReturn(operation);
        output = mapper.readTree("{\"quantity\":1,\"attrs\":{\"accepted\":true}}");
        when(mapping.map(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(executions.saveAndFlush(any())).thenAnswer(i -> { saved.set(i.getArgument(0)); return i.getArgument(0); });
        when(executions.findById(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(executions.findByStepIdAndRequestId(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(saved.get())
            .filter(e -> e.getRequestId().equals(i.getArgument(1))));
        when(executions.findByAttemptId(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get()));
        when(executions.hasUnresolvedTransmission("step")).thenAnswer(i -> saved.get() != null
            && (Set.of("PENDING", "OUTCOME_UNKNOWN").contains(saved.get().getTransportStatus())
                || Set.of("SUCCEEDED", "HTTP_REJECTED", "RESPONSE_REJECTED").contains(saved.get().getTransportStatus())
                    && !"APPLIED".equals(saved.get().getApplyStatus())));
    }

    @Test void claimCommitsBeforeNetworkAndDuplicateRequestNeverResends() {
        when(gateway.execute(any(), any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(saved.get()); assertEquals("PENDING", saved.get().getTransportStatus());
            return new ConnectorHttpOutcome("SUCCEEDED", 200, output, null, true);
        });
        UUID request = UUID.randomUUID();
        var result = service.execute("run", "step", request);
        assertEquals("APPLIED", result.applyStatus());
        assertEquals(output, result.output());
        assertEquals(result.executionId(), service.execute("run", "step", request).executionId());
        verify(gateway, times(1)).execute(any(), any());
        verify(stepService).completeHttp(eq(run), eq("step"), any(), eq("claim"));
    }

    @Test void uncertainTransportBlocksAllFutureAttemptsAndDoesNotCallFailurePolicy() {
        when(gateway.execute(any(), any())).thenReturn(new ConnectorHttpOutcome("OUTCOME_UNKNOWN", null, null, "REMOTE_OUTCOME_UNKNOWN", true));
        var result = service.execute("run", "step", UUID.randomUUID());
        assertEquals("OUTCOME_UNKNOWN", result.transportStatus());
        attempt.setAttemptId("next-attempt"); attempt.setAttemptNo(2);
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        verifyNoInteractions(stepService);
        verify(gateway, times(1)).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "failed"})
    void uncertainResultAfterRunTerminationStaysUnknownAndLocallyLate(String terminalStatus) {
        UUID request = UUID.randomUUID();
        when(gateway.execute(any(), any())).thenAnswer(call -> {
            run.setStatus(terminalStatus);
            step.setStatus("cancelled");
            attempt.setStatus("cancelled");
            return new ConnectorHttpOutcome("OUTCOME_UNKNOWN", null, null, "CANCELLED", true);
        });

        var result = service.execute("run", "step", request);

        assertEquals("OUTCOME_UNKNOWN", result.transportStatus());
        assertEquals("LATE", result.applyStatus());
        assertEquals("REMOTE_OUTCOME_UNKNOWN", result.errorCode());
        assertNull(result.output());
        assertEquals(result, service.execute("run", "step", request));
        verify(gateway, times(1)).execute(any(), any());
        verifyNoInteractions(stepService);
    }

    @Test void cancelledRunKeepsResponseEvidenceWithoutCompletingAnyAttempt() {
        when(gateway.execute(any(), any())).thenAnswer(i -> { run.setStatus("cancelled");
            return new ConnectorHttpOutcome("SUCCEEDED", 200, output, null, true); });
        var result = service.execute("run", "step", UUID.randomUUID());
        assertEquals("LATE", result.applyStatus());
        assertNotNull(saved.get().getOutputJson());
        verifyNoInteractions(stepService);
    }

    @Test void successfulResponseStoresOnlyExplicitlyMappedOutputBeforeApplying() throws Exception {
        JsonNode privateResponse = mapper.readTree("{\"quantity\":1,\"providerPrivate\":\"not-selected\"}");
        JsonNode selected = mapper.createObjectNode().set("quantity", com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("1.2300")));
        when(mapping.map(eq(privateResponse), any())).thenReturn(selected);
        when(gateway.execute(any(), any())).thenReturn(new ConnectorHttpOutcome("SUCCEEDED", 200, privateResponse, null, true));
        when(stepService.completeHttp(eq(run), eq("step"), any(), eq("claim"))).thenAnswer(call -> {
            assertEquals("{\"quantity\":1.2300}", saved.get().getOutputJson());
            return null;
        });
        var result = service.execute("run", "step", UUID.randomUUID());
        assertFalse(saved.get().getOutputJson().contains("providerPrivate"));
        assertEquals(4, result.output().path("quantity").decimalValue().scale());
    }

    @Test void failedResponseMappingRetainsSafeEvidenceButCannotReplayOrSendAgain() {
        when(gateway.execute(any(), any())).thenReturn(new ConnectorHttpOutcome("SUCCEEDED", 200, output, null, true));
        when(mapping.map(eq(output), any())).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "provider-private-value"));
        var result = service.execute("run", "step", UUID.randomUUID());
        assertEquals("SUCCEEDED", result.transportStatus());
        assertEquals("APPLY_FAILED", result.applyStatus());
        assertEquals("RESPONSE_MAPPING_FAILED", result.errorCode());
        assertNull(saved.get().getOutputJson());
        assertThrows(BusinessException.class, () -> service.retryApply("run", "step", result.executionId()));
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        verifyNoInteractions(stepService);
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test void lostOwnerAccessAtPreflightCommitsNotSentEvidenceInsteadOfLeavingClaimPending() {
        doReturn(null).doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireProjectOwnerAccess("project");
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        assertEquals("TRANSPORT_FAILED", saved.get().getTransportStatus());
        assertEquals("HTTP_NOT_SENT", saved.get().getErrorCode());
        assertEquals("APPLY_FAILED", saved.get().getApplyStatus());
        verifyNoInteractions(gateway, stepService);
    }

    @Test void preflightDatabaseFailureRecordsNotSentAndAllowsLocalApplyAfterRecovery() {
        var unavailable = new DataAccessResourceFailureException("preflight database unavailable");
        when(runs.findLockedByFlowRunId("run"))
            .thenReturn(Optional.of(run)).thenThrow(unavailable).thenReturn(Optional.of(run));

        RuntimeException rejected = assertThrows(RuntimeException.class,
            () -> service.execute("run", "step", UUID.randomUUID()));

        assertSame(unavailable, rejected);
        assertNotNull(saved.get());
        assertEquals("TRANSPORT_FAILED", saved.get().getTransportStatus());
        assertEquals("HTTP_NOT_SENT", saved.get().getErrorCode());
        assertEquals("APPLY_FAILED", saved.get().getApplyStatus());
        assertNotNull(saved.get().getCompletedAt());
        assertNull(saved.get().getOutputJson());
        verifyNoInteractions(gateway, stepService);

        var applied = service.retryApply("run", "step", saved.get().getExecutionId());

        assertEquals("TRANSPORT_FAILED", applied.transportStatus());
        assertEquals("APPLIED", applied.applyStatus());
        assertEquals("HTTP_NOT_SENT", applied.errorCode());
        verify(stepService).failHttp(eq(run), eq("step"), any(), eq("claim"));
        verify(gateway, never()).execute(any(), any());
    }

    @Test void profileRecheckDatabaseFailureIsNotSentAndAppliesTheFailurePolicy() {
        var operation = new ConnectorProfileOperation("project", "profile", "pv", 1,
            "https://example.test", null, "send", "POST", "/send", Set.of(200));
        when(profiles.requireOperation("project", "pv", "send"))
            .thenReturn(operation)
            .thenThrow(new DataAccessResourceFailureException("profile database unavailable"));

        var result = service.execute("run", "step", UUID.randomUUID());

        assertEquals("TRANSPORT_FAILED", result.transportStatus());
        assertEquals("HTTP_NOT_SENT", result.errorCode());
        assertEquals("APPLIED", result.applyStatus());
        assertNotNull(saved.get().getCompletedAt());
        assertNull(saved.get().getOutputJson());
        verifyNoInteractions(gateway);
        verify(stepService).failHttp(eq(run), eq("step"), any(), eq("claim"));
        verify(stepService, never()).completeHttp(any(), anyString(), any(), anyString());
    }

    @Test void runtimeFailureAfterEnteringGatewayRemainsUnknownAndCannotResend() {
        when(gateway.execute(any(), any())).thenThrow(new IllegalStateException("provider outcome unavailable"));
        UUID requestId = UUID.randomUUID();

        var first = service.execute("run", "step", requestId);

        assertEquals("OUTCOME_UNKNOWN", first.transportStatus());
        assertEquals("REMOTE_OUTCOME_UNKNOWN", first.errorCode());
        assertEquals("PENDING", first.applyStatus());
        assertNotNull(saved.get().getCompletedAt());
        assertNull(saved.get().getOutputJson());
        assertEquals(first.executionId(), service.execute("run", "step", requestId).executionId());
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        assertThrows(BusinessException.class, () -> service.retryApply("run", "step", first.executionId()));
        verify(gateway, times(1)).execute(any(), any());
        verifyNoInteractions(stepService);
    }

    @Test void ownerRevokedDuringTransmissionCannotReadTheMappingFailureResponseButEvidenceIsCommitted() {
        java.util.concurrent.atomic.AtomicBoolean revoked = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            if (revoked.get()) throw new BusinessException(ErrorCode.FORBIDDEN);
            return null;
        }).when(access).requireProjectOwnerAccess("project");
        when(gateway.execute(any(), any())).thenAnswer(call -> {
            revoked.set(true);
            return new ConnectorHttpOutcome("SUCCEEDED", 200, output, null, true);
        });
        when(mapping.map(eq(output), any())).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "provider-private-value"));

        BusinessException denied = assertThrows(BusinessException.class,
            () -> service.execute("run", "step", UUID.randomUUID()));

        assertEquals(ErrorCode.FORBIDDEN, denied.getErrorCode());
        assertEquals("SUCCEEDED", saved.get().getTransportStatus());
        assertEquals("APPLY_FAILED", saved.get().getApplyStatus());
        assertEquals("RESPONSE_MAPPING_FAILED", saved.get().getErrorCode());
        assertNull(saved.get().getOutputJson());
        assertNotNull(saved.get().getCompletedAt());
        verifyNoInteractions(stepService);
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test void localApplyFailureCanBeRetriedWithoutSendingHttpAgain() {
        when(gateway.execute(any(), any())).thenReturn(new ConnectorHttpOutcome("SUCCEEDED", 200, output, null, true));
        when(stepService.completeHttp(eq(run), eq("step"), any(), eq("claim")))
            .thenThrow(new BusinessException(org.myweb.flowmat.global.exception.ErrorCode.CONFLICT, "provider-private-value"))
            .thenReturn(null);
        var first = service.execute("run", "step", UUID.randomUUID());
        assertEquals("APPLY_FAILED", first.applyStatus());
        assertEquals("LOCAL_APPLY_FAILED", first.errorCode());
        var second = service.retryApply("run", "step", first.executionId());
        assertEquals("APPLIED", second.applyStatus());
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test void simulationAndProductionNeverCallTransport() {
        run.setRunType("simulation");
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        run.setRunType("actual"); run.setProductionRunId("production");
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        verifyNoInteractions(gateway);
    }

    @Test void ownerRefusalHappensBeforeReadingBindingOrClaiming() {
        doThrow(new BusinessException(org.myweb.flowmat.global.exception.ErrorCode.FORBIDDEN))
            .when(access).requireProjectOwnerAccess("project");
        assertThrows(BusinessException.class, () -> service.execute("run", "step", UUID.randomUUID()));
        verifyNoInteractions(workflow, profiles, mapping, gateway, executions);
    }

    @Test void boundedCallerBodyRejectsDuplicateKeysAndTrailingRootsBeforeAnyClaim() {
        String id = UUID.randomUUID().toString();
        for (String body : List.of("{\"requestId\":\"" + id + "\",\"requestId\":\"" + id + "\"}",
            "{\"requestId\":\"" + id + "\"} {}", "{\"requestId\":\"" + id + "\",\"target\":\"x\"}",
            " ".repeat(1025))) {
            assertThrows(BusinessException.class, () -> service.execute("run", "step",
                new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        verifyNoInteractions(gateway, executions);
    }

    @Test void bodyIsNotReadWhenOwnerAccessIsRefused() throws Exception {
        doThrow(new BusinessException(org.myweb.flowmat.global.exception.ErrorCode.FORBIDDEN))
            .when(access).requireProjectOwnerAccess("project");
        var body = mock(java.io.InputStream.class);
        assertThrows(BusinessException.class, () -> service.execute("run", "step", body));
        verifyNoInteractions(body, gateway, executions);
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
