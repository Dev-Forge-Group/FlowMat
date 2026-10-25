package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.connector.application.ConnectorTargetPolicy;
import org.myweb.flowmat.domain.connector.application.publicapi.*;
import org.myweb.flowmat.domain.flowrun.application.FlowRunStepService;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@AutoConfigureMockMvc
class FlowRunHttpExecutionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired FlowRunStepService stepService;
    @MockitoBean ConnectorTargetPolicy targets;
    @MockitoBean ConnectorHttpGateway gateway;
    @MockitoSpyBean ConnectorMappingQuery mapping;
    @MockitoSpyBean FlowRunStepRepository stepRepository;

    @BeforeEach void policy() {
        reset(targets, gateway);
        when(targets.normalizeOrigin(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ConnectorProfileOperation operation = call.getArgument(0);
            assertTrue(jdbc.queryForObject("select count(*) = 1 from flow_run_http_execution where transport_status='PENDING' and profile_version_id=?", Boolean.class, operation.profileVersionId()));
            return new ConnectorHttpOutcome("SUCCEEDED", 200, json.readTree("{\"quantity\":2,\"attrs\":{\"accepted\":true}}"), null, true);
        }).when(gateway).execute(any(), any());
    }

    @Test void actualPublishedBindingExecutesOnceAndEvidenceDoesNotExposeInternalResponse() throws Exception {
        Fixture f = fixture("actual");
        String request = request();
        JsonNode result = data(call(DEMO_OWNER, post(f.path() + "/execute"), request));
        assertEquals("SUCCEEDED", result.path("transportStatus").asText());
        assertEquals("APPLIED", result.path("applyStatus").asText());
        assertEquals(2, result.path("output").path("quantity").asInt());
        assertEquals("completed", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, f.step()));
        assertEquals(result.path("executionId"), data(call(DEMO_OWNER, post(f.path() + "/execute"), request)).path("executionId"));
        JsonNode evidence = data(call(DEMO_OWNER, get(f.path() + "/http-executions"), null)).get(0);
        for (String name : List.of("responseJson", "response", "claimToken", "requestHash", "credentialAlias")) assertFalse(evidence.has(name));
        verify(gateway, times(1)).execute(any(), any());
        assertEquals(1, jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, f.step()));
    }

    @Test void actualExecutionPreservesExactCallerNumbersThroughStartClaimAndMappedEvidence() throws Exception {
        Fixture f = fixture("actual", false, "{\"quantity\":9007199254740993.123456789,\"attrs\":{\"scaled\":1.2300}}");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            JsonNode request = call.getArgument(1);
            assertEquals(new java.math.BigDecimal("9007199254740993.123456789"), request.path("quantity").decimalValue());
            assertEquals(new java.math.BigDecimal("1.2300"), request.path("attrs").path("scaled").decimalValue());
            return new ConnectorHttpOutcome("SUCCEEDED", 200, request, null, true);
        }).when(gateway).execute(any(), any());
        JsonNode result = data(call(DEMO_OWNER, post(f.path() + "/execute"), request()));
        assertEquals("APPLIED", result.path("applyStatus").asText());
        assertEquals(new java.math.BigDecimal("9007199254740993.123456789"), result.path("output").path("quantity").decimalValue());
        assertEquals(new java.math.BigDecimal("1.2300"), result.path("output").path("attrs").path("scaled").decimalValue());
        assertTrue(result.path("outputJson").asText().contains("9007199254740993.123456789"));
        assertTrue(result.path("outputJson").asText().contains("1.2300"));
        String stored = jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, f.step());
        assertTrue(stored.contains("9007199254740993.123456789"));
        assertTrue(stored.contains("1.2300"));
    }

    @Test @Timeout(60) void concurrentDuplicateSeesCommittedClaimAndNeverSendsAgain() throws Exception {
        Fixture f = fixture("actual");
        CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sent.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS));
            return success();
        }).when(gateway).execute(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            String body = request();
            Future<JsonNode> first = pool.submit(() -> data(call(DEMO_OWNER, post(f.path() + "/execute"), body)));
            try {
                assertTrue(sent.await(20, TimeUnit.SECONDS));
                JsonNode duplicate = data(call(DEMO_OWNER, post(f.path() + "/execute"), body));
                assertEquals("PENDING", duplicate.path("transportStatus").asText());
                call(DEMO_OWNER, post(f.path() + "/execute"), request()).andExpect(status().isConflict());
                call(DEMO_OWNER, post(f.path() + "/complete"), "{\"outputSnapshot\":{}}").andExpect(status().isConflict());
                call(DEMO_OWNER, post(f.path() + "/fail"), "{\"errorCode\":\"BYPASS\"}").andExpect(status().isConflict());
            } finally { release.countDown(); }
            assertEquals("APPLIED", first.get(20, TimeUnit.SECONDS).path("applyStatus").asText());
        }
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test @Timeout(60) void cancellationKeepsLateEvidenceWithoutCompletingTheCancelledAttempt() throws Exception {
        Fixture f = fixture("actual");
        CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { sent.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); return success(); }).when(gateway).execute(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<JsonNode> execution = pool.submit(() -> data(call(DEMO_OWNER, post(f.path() + "/execute"), request())));
            try {
                assertTrue(sent.await(20, TimeUnit.SECONDS));
                data(call(DEMO_OWNER, post("/flow-runs/" + f.run() + "/cancel"), "{\"reason\":\"Cancel while the owned test HTTP request is pending\"}"));
            } finally { release.countDown(); }
            assertEquals("LATE", execution.get(20, TimeUnit.SECONDS).path("applyStatus").asText());
        }
        assertEquals("cancelled", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, f.step()));
        assertNotNull(jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, f.step()));
        assertNull(jdbc.queryForObject("select output_snapshot::text from flow_run_step where step_id=?", String.class, f.step()));
    }

    @Test void localPersistenceFailureRollsBackCompletionAndRetriesWithoutAnotherTransmission() throws Exception {
        Fixture f = fixture("actual");
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            var step = (org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep) call.getArgument(0);
            assertTrue(entities.contains(step), "This failure injection substitutes flushing an existing managed step.");
            if ("completed".equals(step.getStatus()) && fail.compareAndSet(true, false)) {
                throw new BusinessException(ErrorCode.CONFLICT, "private-response-value");
            }
            // The Spring Data repository is an interface proxy; flush the managed entity instead of invoking an abstract real method.
            entities.flush();
            return step;
        }).when(stepRepository).saveAndFlush(any());
        JsonNode first = data(call(DEMO_OWNER, post(f.path() + "/execute"), request()));
        assertEquals("APPLY_FAILED", first.path("applyStatus").asText());
        assertEquals("LOCAL_APPLY_FAILED", first.path("errorCode").asText());
        assertFalse(first.toString().contains("private-response-value"));
        assertEquals("running", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, f.step()));
        assertNotNull(jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, f.step()));
        JsonNode retried = data(call(DEMO_OWNER, post(f.path() + "/http-executions/" + first.path("executionId").asText() + "/apply"), null));
        assertEquals("APPLIED", retried.path("applyStatus").asText());
        assertEquals("completed", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, f.step()));
        assertEquals("completed", jdbc.queryForObject("select status from flow_run_step_attempt where step_id=? and attempt_no=1", String.class, f.step()));
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test void failedResponseMappingStoresNoRawResponseAndCannotBeReplayed() throws Exception {
        Fixture f = fixture("actual");
        doAnswer(call -> {
            JsonNode source = call.getArgument(0);
            if (source.has("attrs")) throw new BusinessException(ErrorCode.BAD_REQUEST, "private-response-value");
            return call.callRealMethod();
        }).when(mapping).map(any(), any());
        JsonNode result = data(call(DEMO_OWNER, post(f.path() + "/execute"), request()));
        assertEquals("SUCCEEDED", result.path("transportStatus").asText());
        assertEquals("APPLY_FAILED", result.path("applyStatus").asText());
        assertEquals("RESPONSE_MAPPING_FAILED", result.path("errorCode").asText());
        assertNull(jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, f.step()));
        call(DEMO_OWNER, post(f.path() + "/http-executions/" + result.path("executionId").asText() + "/apply"), null)
            .andExpect(status().isConflict());
        call(DEMO_OWNER, post(f.path() + "/execute"), request()).andExpect(status().isConflict());
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test @Timeout(60) void timeoutRetryCannotResendOrReportOverAnEarlierSuccessfulButLateTransmission() throws Exception {
        Fixture f = fixture("actual", true);
        CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { sent.countDown(); assertTrue(release.await(20, TimeUnit.SECONDS)); return success(); }).when(gateway).execute(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<JsonNode> execution = pool.submit(() -> data(call(DEMO_OWNER, post(f.path() + "/execute"), request())));
            try {
                assertTrue(sent.await(20, TimeUnit.SECONDS));
                String firstAttempt = jdbc.queryForObject("select attempt_id from flow_run_step_attempt where step_id=? and attempt_no=1", String.class, f.step());
                jdbc.update("update flow_run_step_attempt set started_at=now()-interval '2 minutes', timeout_at=now()-interval '1 minute' where attempt_id=?", firstAttempt);
                assertTrue(stepService.timeOut(firstAttempt));
                assertEquals(2, jdbc.queryForObject("select count(*) from flow_run_step_attempt where step_id=?", Integer.class, f.step()));
            } finally { release.countDown(); }
            JsonNode result = execution.get(20, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", result.path("transportStatus").asText());
            assertEquals("LATE", result.path("applyStatus").asText());
        }
        call(DEMO_OWNER, post(f.path() + "/execute"), request()).andExpect(status().isConflict());
        call(DEMO_OWNER, post(f.path() + "/complete"), "{\"attemptNo\":2,\"outputSnapshot\":{}}").andExpect(status().isConflict());
        call(DEMO_OWNER, post(f.path() + "/fail"), "{\"attemptNo\":2,\"errorCode\":\"BYPASS\"}").andExpect(status().isConflict());
        assertEquals("running", jdbc.queryForObject("select status from flow_run_step_attempt where step_id=? and attempt_no=2", String.class, f.step()));
        verify(gateway, times(1)).execute(any(), any());
    }

    @Test void uncertainOutcomeBlocksAnotherSendAndOrdinaryReports() throws Exception {
        Fixture f = fixture("actual");
        doReturn(new ConnectorHttpOutcome("OUTCOME_UNKNOWN", null, null, "PRIVATE", true)).when(gateway).execute(any(), any());
        JsonNode first = data(call(DEMO_OWNER, post(f.path() + "/execute"), request()));
        assertEquals("OUTCOME_UNKNOWN", first.path("transportStatus").asText());
        assertEquals("REMOTE_OUTCOME_UNKNOWN", first.path("errorCode").asText());
        call(DEMO_OWNER, post(f.path() + "/execute"), request()).andExpect(status().isConflict());
        call(DEMO_OWNER, post(f.path() + "/fail"), "{\"errorCode\":\"CLEAR\"}").andExpect(status().isConflict());
        verify(gateway, times(1)).execute(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"owner,complete", "owner,fail", "editor,complete", "editor,fail"})
    void actualBoundStepRequiresHttpEvidenceBeforeAnyOrdinaryReport(String role, String action) throws Exception {
        Fixture f = fixture("actual");
        data(call(DEMO_OWNER, post(f.path() + "/start"), null));
        String user = "owner".equals(role) ? DEMO_OWNER : member(role);
        String body = "complete".equals(action) ? "{\"outputSnapshot\":{\"quantity\":2}}" : "{\"errorCode\":\"BYPASS\"}";
        call(user, post(f.path() + "/" + action), body).andExpect(status().isConflict());
        assertEquals("running", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, f.step()));
        assertEquals(0, jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, f.step()));
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"simulation", "test", "dry_run"})
    void nonActualBoundStepCanStillBeReportedWithoutSendingHttp(String runType) throws Exception {
        Fixture f = fixture(runType);
        data(call(DEMO_OWNER, post(f.path() + "/start"), null));
        JsonNode result = data(call(DEMO_OWNER, post(f.path() + "/complete"), "{\"outputSnapshot\":{\"quantity\":2}}"));
        assertEquals("completed", result.path("status").asText());
        verifyNoInteractions(gateway);
    }

    @Test void simulationViewerEditorOutsiderAndMalformedBodiesCannotTransmit() throws Exception {
        Fixture simulation = fixture("simulation");
        call(DEMO_OWNER, post(simulation.path() + "/execute"), request()).andExpect(status().isConflict());
        Fixture actual = fixture("actual");
        for (String role : List.of("viewer", "editor")) {
            String user = member(role);
            call(user, post(actual.path() + "/execute"), request()).andExpect(status().isForbidden());
        }
        call("http-execution-outsider", post(actual.path() + "/execute"), request()).andExpect(status().isForbidden());
        mvc.perform(post(actual.path() + "/execute").contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isUnauthorized());
        String id = UUID.randomUUID().toString();
        for (String invalid : List.of("{", "null", "{}", "{\"requestId\":1}", " ".repeat(1025),
            "{\"requestId\":\"" + id + "\",\"requestId\":\"" + id + "\"}", "{\"requestId\":\"" + id + "\"} {}")) {
            call(DEMO_OWNER, post(actual.path() + "/execute"), invalid).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(gateway);
        assertEquals(0, jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, actual.step()));
    }

    @Test void previewShowsTheExactPublishedRequestWithoutStartingOrClaimingAnything() throws Exception {
        Fixture f = fixture("actual", false, "{\"quantity\":9007199254740993.123456789,\"attrs\":{\"scaled\":1.2300,\"nullable\":null}}");
        List<Object> before = previewState(f);
        ResultActions response = call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null);
        String cacheControl = response.andReturn().getResponse().getHeader("Cache-Control");
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-store"));
        JsonNode preview = data(response);
        assertEquals("POST", preview.path("method").asText());
        assertEquals("https://api.example.com/send", preview.path("target").asText());
        assertEquals("send", preview.path("operationId").asText());
        assertTrue(preview.path("hasRequestBody").asBoolean());
        assertTrue(preview.path("requestJson").asText().contains("9007199254740993.123456789"));
        assertTrue(preview.path("requestJson").asText().contains("1.2300"));
        assertTrue(preview.path("requestJson").asText().contains("\"nullable\":null"));
        assertFalse(preview.has("credentialAlias"));
        assertEquals(before, previewState(f));
        verifyNoInteractions(gateway);
        String shown = preview.path("requestJson").asText();
        doAnswer(call -> {
            assertEquals(shown, json.writeValueAsString(call.getArgument(1)));
            return success();
        }).when(gateway).execute(any(), any());
        assertEquals("APPLIED", data(call(DEMO_OWNER, post(f.path() + "/execute"), request())).path("applyStatus").asText());
    }

    @Test void previewUsesThePublishedVersionAfterTheProfileAndDraftBindingChange() throws Exception {
        Fixture f = fixture("actual");
        JsonNode before = data(call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null));
        JsonNode profile = data(call(DEMO_OWNER, get("/projects/" + DEMO_PROJECT + "/http-profiles"), null)).get(0);
        data(call(DEMO_OWNER, put("/projects/" + DEMO_PROJECT + "/http-profiles/" + profile.path("profileId").asText()), """
            {"expectedVersion":0,"name":"Changed draft profile","baseOrigin":"https://changed.example.com","enabled":true,
             "operations":[{"operationId":"send","method":"PUT","relativePath":"/changed","successStatuses":[200]}]}
            """));
        String process = jdbc.queryForObject("select node_id from flow_run_step where step_id=?", String.class, f.step());
        JsonNode binding = data(call(DEMO_OWNER, get("/processes/" + process + "/execution-binding"), null));
        data(call(DEMO_OWNER, put("/processes/" + process + "/execution-binding"), "{\"expectedVersion\":"
            + binding.path("version").asLong() + ",\"profileVersionId\":\"" + before.path("profileVersionId").asText()
            + "\",\"operationId\":\"send\",\"outputPortId\":\"" + binding.path("outputPortId").asText()
            + "\",\"requestMapping\":{\"literal\":{\"draft\":\"changed\"}},\"responseMapping\":{\"from\":\"\"}}"));
        assertEquals(before, data(call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null)));
        verifyNoInteractions(gateway);
    }

    @Test void previewRequiresAnOwnerAndDoesNotExposeOtherSteps() throws Exception {
        Fixture f = fixture("actual");
        for (String role : List.of("viewer", "editor")) {
            call(member(role), get(f.path() + "/http-request-preview"), null).andExpect(status().isForbidden());
        }
        call("http-preview-outsider", get(f.path() + "/http-request-preview"), null).andExpect(status().isForbidden());
        mvc.perform(get(f.path() + "/http-request-preview")).andExpect(status().isUnauthorized());
        Fixture other = fixture("actual");
        call(DEMO_OWNER, get("/flow-runs/" + f.run() + "/steps/" + other.step() + "/http-request-preview"), null)
            .andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
        assertEquals(0, jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, f.step()));
    }

    @ParameterizedTest @ValueSource(strings = {"simulation", "test", "dry_run"})
    void previewRefusesRunsThatCannotExecuteHttp(String runType) throws Exception {
        Fixture f = fixture(runType);
        call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null).andExpect(status().isConflict());
        verifyNoInteractions(gateway);
    }

    @Test void previewDoesNotResurrectCancelledRuns() throws Exception {
        Fixture f = fixture("actual");
        data(call(DEMO_OWNER, post("/flow-runs/" + f.run() + "/cancel"), "{\"reason\":\"Cancel before preview\"}"));
        List<Object> before = previewState(f);
        call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null).andExpect(status().isConflict());
        assertEquals(before, previewState(f));
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "POST"})
    void previewDistinguishesNoBodyFromAnExplicitJsonNullAndMatchesExecution(String method) throws Exception {
        Fixture f = fixture("actual", false, "{}", method, "{\"literal\":null}");
        List<Object> before = previewState(f);
        JsonNode preview = data(call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null));
        assertEquals(method, preview.path("method").asText());
        assertEquals(!method.equals("GET"), preview.path("hasRequestBody").asBoolean());
        if (method.equals("GET")) assertTrue(preview.path("requestJson").isNull());
        else assertEquals("null", preview.path("requestJson").asText());
        assertEquals(before, previewState(f));
        verifyNoInteractions(gateway);
        doAnswer(call -> {
            assertTrue(((JsonNode) call.getArgument(1)).isNull());
            return success();
        }).when(gateway).execute(any(), any());
        assertEquals("APPLIED", data(call(DEMO_OWNER, post(f.path() + "/execute"), request())).path("applyStatus").asText());
    }

    @Test void invalidGetMappingIsRefusedBeforeBothPreviewAndExecutionCanStartAnAttempt() throws Exception {
        Fixture f = fixture("actual", false, "{\"quantity\":2}", "GET", "{\"from\":\"\"}");
        List<Object> before = previewState(f);
        call(DEMO_OWNER, get(f.path() + "/http-request-preview"), null).andExpect(status().isBadRequest());
        call(DEMO_OWNER, post(f.path() + "/execute"), request()).andExpect(status().isBadRequest());
        assertEquals(before, previewState(f));
        verifyNoInteractions(gateway);
    }

    private List<Object> previewState(Fixture f) {
        return List.of(
            jdbc.queryForMap("select status,input_snapshot::text from flow_run_step where step_id=?", f.step()),
            jdbc.queryForObject("select count(*) from flow_run_step_attempt where step_id=?", Integer.class, f.step()),
            jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, f.step()),
            jdbc.queryForObject("select count(*) from flow_run_event where flow_run_id=?", Integer.class, f.run()));
    }

    private Fixture fixture(String runType) throws Exception {
        return fixture(runType, false);
    }
    private Fixture fixture(String runType, boolean retry) throws Exception {
        return fixture(runType, retry, "{\"quantity\":2}");
    }
    private Fixture fixture(String runType, boolean retry, String inputJson) throws Exception {
        return fixture(runType, retry, inputJson, "POST", "{\"from\":\"\"}");
    }
    private Fixture fixture(String runType, boolean retry, String inputJson, String method, String requestMapping) throws Exception {
        String workflow = data(call(DEMO_OWNER, post("/workflows"), json.writeValueAsString(Map.of("projectId", DEMO_PROJECT, "workflowName", "HTTP run " + UUID.randomUUID())))).path("workflowId").asText();
        String process = data(call(DEMO_OWNER, post("/processes"), json.writeValueAsString(Map.of("workflowId", workflow, "processName", "HTTP")))).path("processId").asText();
        if (retry) {
            String source = data(call(DEMO_OWNER, post("/processes"), json.writeValueAsString(Map.of("workflowId", workflow, "processName", "Source")))).path("processId").asText();
            data(call(DEMO_OWNER, post("/process-connections"), json.writeValueAsString(Map.of("workflowId", workflow, "fromProcessId", source, "toProcessId", process, "failurePolicy", "retry"))));
            data(call(DEMO_OWNER, put("/processes/" + process + "/execution-policy"), "{\"expectedVersion\":0,\"timeoutSeconds\":30,\"retryLimit\":1}"));
        }
        String output = data(call(DEMO_OWNER, post("/process-ios"), json.writeValueAsString(Map.of("processId", process, "direction", "output", "resourceType", "data", "ioName", "Response", "requiredYn", "N")))).path("processIoId").asText();
        JsonNode profile = data(call(DEMO_OWNER, post("/projects/" + DEMO_PROJECT + "/http-profiles"), """
            {"name":"HTTP execution","baseOrigin":"https://api.example.com","enabled":true,
             "operations":[{"operationId":"send","method":"%s","relativePath":"/send","successStatuses":[200]}]}
            """.formatted(method)));
        data(call(DEMO_OWNER, put("/processes/" + process + "/execution-binding"), "{\"expectedVersion\":0,\"profileVersionId\":\""
            + profile.path("profileVersionId").asText() + "\",\"operationId\":\"send\",\"outputPortId\":\"" + output
            + "\",\"requestMapping\":" + requestMapping + ",\"responseMapping\":{\"from\":\"\"}}"));
        JsonNode published = data(call(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null));
        assertEquals(4, published.path("snapshot").path("schemaVersion").asInt());
        String run = data(call(DEMO_OWNER, post("/flow-runs/graph"), "{\"workflowId\":\"" + workflow
            + "\",\"workflowRevisionId\":\"" + published.path("workflowRevisionId").asText() + "\",\"runType\":\"" + runType
            + "\",\"inputPayload\":" + inputJson + "}")).path("flowRunId").asText();
        String step = data(call(DEMO_OWNER, get("/flow-runs/" + run + "/steps"), null)).get(0).path("stepId").asText();
        if (retry) {
            data(call(DEMO_OWNER, post("/flow-runs/" + run + "/steps/" + step + "/start"), null));
            data(call(DEMO_OWNER, post("/flow-runs/" + run + "/steps/" + step + "/complete"), "{\"outputSnapshot\":{\"quantity\":2}}"));
            step = data(call(DEMO_OWNER, get("/flow-runs/" + run + "/steps"), null)).get(1).path("stepId").asText();
        }
        return new Fixture(run, step);
    }
    private ConnectorHttpOutcome success() throws Exception { return new ConnectorHttpOutcome("SUCCEEDED", 200, json.readTree("{\"quantity\":2,\"attrs\":{\"accepted\":true}}"), null, true); }
    private String request() { return "{\"requestId\":\"" + UUID.randomUUID() + "\"}"; }
    private String member(String role) {
        String user = "http-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status,created_at) values(?,?,?,?,?,now())", UUID.randomUUID().toString(), DEMO_PROJECT, user, role, "active");
        return user;
    }
    private ResultActions call(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
    private JsonNode data(ResultActions result) throws Exception {
        return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private record Fixture(String run, String step) { String path() { return "/flow-runs/" + run + "/steps/" + step; } }
}
