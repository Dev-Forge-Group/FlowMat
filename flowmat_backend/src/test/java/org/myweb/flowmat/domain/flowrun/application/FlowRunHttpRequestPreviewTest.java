package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunHttpExecutionRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class FlowRunHttpRequestPreviewTest {
    private static final String PRIVATE_SOURCE = "private-source-value";
    private static final String SNAPSHOT = """
        {"schemaVersion":4,"processes":[{"processId":"node"}],"connections":[],
         "processIos":[{"processIoId":"out","processId":"node","direction":"output"}],
         "nodeBindings":[{"processId":"node","profileVersionId":"pinned-version","operationId":"send",
          "outputPortId":"out","requestMapping":{"object":{"amount":{"from":"/amount"},
          "constant":{"literal":1.2300},"optional":{"from":"/missing","required":false},
          "nullable":{"from":"/nullable"}}},"responseMapping":{"from":""}}]}
        """;

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
    private FlowRunHttpExecutionService service;
    private FlowRun run;
    private FlowRunStep step;
    private JsonNode mapped;

    @BeforeEach
    void setup() throws Exception {
        service = new FlowRunHttpExecutionService(runs, steps, attempts, executions, access, workflow,
            profiles, mapping, gateway, stepService, ids, mapper, new TestTransactions(), new FlowRunHttpInFlightRequests());
        run = new FlowRun();
        run.setFlowRunId("run"); run.setProjectId("project"); run.setWorkflowId("workflow");
        run.setWorkflowRevisionId("published-revision"); run.setStatus("running");
        run.setRunType("actual"); run.setExecutionMode("graph");
        step = new FlowRunStep();
        step.setStepId("step"); step.setFlowRunId("run"); step.setNodeId("node");
        step.setStatus("planned");
        step.setInputSnapshot("{\"amount\":12345678901234567890.123400,\"nullable\":null,\"private\":\"" + PRIVATE_SOURCE + "\"}");
        when(runs.findById("run")).thenReturn(Optional.of(run));
        when(steps.findByStepIdAndFlowRunId("step", "run")).thenReturn(Optional.of(step));
        revision(SNAPSHOT);
        operation("POST");
        mapped = exact("{\"amount\":12345678901234567890.123400,\"constant\":1.2300,\"nullable\":null}");
        when(mapping.map(any(), any())).thenAnswer(call -> mapped);
    }

    @AfterEach
    void previewNeverClaimsStartsOrSends() {
        verifyNoInteractions(attempts, executions, gateway, stepService, ids);
        verify(runs, never()).findLockedByFlowRunId(anyString());
        verify(runs, never()).save(any());
        verify(runs, never()).saveAndFlush(any());
        verify(steps, never()).save(any());
        verify(steps, never()).saveAndFlush(any());
        assertEquals("running", run.getStatus());
    }

    @Test
    void postUsesPublishedBindingAndPreservesSourceAndMappingDecimalScale() {
        String inputBefore = step.getInputSnapshot();
        var result = service.preview("run", "step");

        assertEquals("pinned-version", result.profileVersionId());
        assertEquals("send", result.operationId());
        assertEquals("POST", result.method());
        assertEquals("https://approved.example/api/send", result.target());
        assertTrue(result.hasRequestBody());
        assertEquals("{\"amount\":12345678901234567890.123400,\"constant\":1.2300,\"nullable\":null}", result.requestJson());
        assertFalse(result.requestJson().contains("optional"));
        assertEquals(inputBefore, step.getInputSnapshot());
        assertEquals("planned", step.getStatus());

        var source = ArgumentCaptor.forClass(JsonNode.class);
        var definition = ArgumentCaptor.forClass(JsonNode.class);
        verify(mapping).map(source.capture(), definition.capture());
        assertEquals(6, source.getValue().path("amount").decimalValue().scale());
        assertEquals("12345678901234567890.123400", source.getValue().path("amount").decimalValue().toPlainString());
        assertTrue(source.getValue().path("nullable").isNull());
        assertTrue(source.getValue().path("missing").isMissingNode());
        assertEquals(4, definition.getValue().path("object").path("constant").path("literal").decimalValue().scale());
        verify(profiles).requireOperation("project", "pinned-version", "send");
        verifyNoMoreInteractions(profiles);
    }

    @Test
    void alreadyRunningStepCanBePreviewedWithoutStartingAnotherAttempt() {
        step.setStatus("running");
        assertTrue(service.preview("run", "step").hasRequestBody());
        assertEquals("running", step.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void nullPayloadIsStillAnExplicitJsonBodyForBodyMethods(String method) {
        operation(method);
        mapped = NullNode.getInstance();
        var result = service.preview("run", "step");
        assertEquals(method, result.method());
        assertTrue(result.hasRequestBody());
        assertEquals("null", result.requestJson());
    }

    @Test
    void getWithNullMappingHasNoRequestBody() {
        operation("GET");
        mapped = NullNode.getInstance();
        var result = service.preview("run", "step");
        assertEquals("GET", result.method());
        assertFalse(result.hasRequestBody());
        assertNull(result.requestJson());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "false", "0", "\"private-source-value\""})
    void getRejectsAnyNonNullMappedRequestWithoutExposingIt(String json) throws Exception {
        operation("GET");
        mapped = exact(json);
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("run", "step"));
        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains(PRIVATE_SOURCE));
    }

    @Test
    void ownerAuthorizationPrecedesStepRevisionProfileAndMappingReads() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireProjectOwnerAccess("project");
        BusinessException denied = assertThrows(BusinessException.class, () -> service.preview("run", "step"));
        assertEquals(ErrorCode.FORBIDDEN, denied.getErrorCode());
        verifyNoInteractions(steps, workflow, profiles, mapping);
    }

    @Test
    void accessIsCheckedBeforeDerivingAnyPrivateRequest() {
        service.preview("run", "step");
        var order = inOrder(runs, access, steps, workflow, profiles, mapping);
        order.verify(runs).findById("run");
        order.verify(access).requireProjectOwnerAccess("project");
        order.verify(steps).findByStepIdAndFlowRunId("step", "run");
        order.verify(workflow).findRevision("published-revision", "workflow");
        order.verify(profiles).requireOperation("project", "pinned-version", "send");
        order.verify(mapping).map(any(), any());
    }

    @Test
    void missingRunIsNotFoundBeforeLookingAtTheStep() {
        when(runs.findById("run")).thenReturn(Optional.empty());
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(access, steps, workflow, profiles, mapping);
    }

    @Test
    void stepOutsideThisRunIsNotFoundWithoutReadingItsSource() {
        when(steps.findByStepIdAndFlowRunId("step", "run")).thenReturn(Optional.empty());
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(workflow, profiles, mapping);
    }

    @ParameterizedTest
    @ValueSource(strings = {"planned", "completed", "cancelled", "failed"})
    void onlyRunningRunsCanBePreviewed(String status) {
        run.setStatus(status);
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("run", "step"));
        assertEquals(ErrorCode.CONFLICT, rejected.getErrorCode());
        verifyNoInteractions(workflow, profiles, mapping);
        run.setStatus("running");
    }

    @ParameterizedTest
    @ValueSource(strings = {"simulation", "test", "dry_run"})
    void previewDoesNotSuggestActualTransmissionForNonActualRuns(String type) {
        run.setRunType(type);
        assertCode(ErrorCode.CONFLICT);
        verifyNoInteractions(workflow, profiles, mapping);
    }

    @Test
    void manualExecutionModeIsRejected() {
        run.setExecutionMode("manual");
        assertCode(ErrorCode.CONFLICT);
        verifyNoInteractions(workflow, profiles, mapping);
    }

    @Test
    void productionLinkedRunIsRejected() {
        run.setProductionRunId("production-run");
        assertCode(ErrorCode.CONFLICT);
        verifyNoInteractions(workflow, profiles, mapping);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled", "skipped"})
    void terminalStepsAreRejectedBeforeMappingTheirOldInput(String status) {
        step.setStatus(status);
        assertCode(ErrorCode.CONFLICT);
        assertEquals(status, step.getStatus());
        verifyNoInteractions(workflow, profiles, mapping);
    }

    @Test
    void missingPublishedRevisionIsNotFound() {
        when(workflow.findRevision("published-revision", "workflow")).thenReturn(Optional.empty());
        assertCode(ErrorCode.NOT_FOUND);
        verifyNoInteractions(profiles, mapping);
    }

    @Test
    void missingPublishedBindingIsRejectedWithoutSelectingTheCurrentProfile() {
        revision("{\"schemaVersion\":3,\"processes\":[{\"processId\":\"node\"}],\"processIos\":[]}");
        assertCode(ErrorCode.CONFLICT);
        verifyNoInteractions(profiles, mapping);
    }

    @Test
    void disabledOrWithdrawnPinnedOperationStopsBeforeMappingPrivateData() {
        when(profiles.requireOperation("project", "pinned-version", "send"))
            .thenThrow(new BusinessException(ErrorCode.CONFLICT, "profile is disabled"));
        assertCode(ErrorCode.CONFLICT);
        verifyNoInteractions(mapping);
    }

    @Test
    void boundedMappingFailureNeverIncludesPrivateSourceInThePublicException() {
        when(mapping.map(any(), any())).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, PRIVATE_SOURCE));
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("run", "step"));
        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains(PRIVATE_SOURCE));
    }

    @Test
    void unexpectedMappingFailureIsAlsoAStableSafeBusinessRefusal() {
        when(mapping.map(any(), any())).thenThrow(new IllegalArgumentException(PRIVATE_SOURCE));
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("run", "step"));
        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains(PRIVATE_SOURCE));
    }

    private void assertCode(ErrorCode code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.preview("run", "step")).getErrorCode());
    }

    private void revision(String snapshot) {
        when(workflow.findRevision("published-revision", "workflow")).thenReturn(Optional.of(
            new WorkflowProductionQuery.Revision("published-revision", "published", snapshot, 7)));
    }

    private void operation(String method) {
        when(profiles.requireOperation("project", "pinned-version", "send")).thenReturn(
            new ConnectorProfileOperation("project", "profile", "pinned-version", 7, "https://approved.example",
                "private-credential-alias", "send", method, "/api/send", Set.of(200)));
    }

    private JsonNode exact(String value) throws Exception {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value);
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
