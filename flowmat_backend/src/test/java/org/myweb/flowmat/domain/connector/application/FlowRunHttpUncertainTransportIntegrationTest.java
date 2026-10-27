package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The owned server accepts a complete request and closes before sending any response headers. */
@AutoConfigureMockMvc
class FlowRunHttpUncertainTransportIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectorHttpProperties properties;
    @MockitoSpyBean ConnectorTargetPolicy policy;

    private HttpServer server;
    private ExecutorService serverWorkers;
    private String origin;
    private boolean previousEnabled;
    private boolean previousPrivateHttp;
    private List<String> previousOrigins;
    private final AtomicInteger wireRequests = new AtomicInteger();
    private final AtomicInteger providerEffects = new AtomicInteger();
    private final AtomicReference<String> profileVersion = new AtomicReference<>();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

    @BeforeEach
    void startOwnedServerThatLosesTheResponse() throws Exception {
        previousEnabled = properties.isEnabled();
        previousPrivateHttp = properties.isAllowPrivateHttp();
        previousOrigins = properties.getAllowedOrigins();
        InetAddress ownedAddress = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(ownedAddress, 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/send", exchange -> {
            wireRequests.incrementAndGet();
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = exchange.getRequestBody().readNBytes(1025);
                assertTrue(body.length <= 1024, "The fixture sends only a small, bounded JSON object.");
                assertEquals(json.readTree("{\"quantity\":3}"),
                    json.readTree(new String(body, StandardCharsets.UTF_8)));
                assertTrue(jdbc.queryForObject("select count(*) = 1 from flow_run_http_execution "
                        + "where transport_status='PENDING' and profile_version_id=?",
                    Boolean.class, profileVersion.get()), "The claim must commit before the provider receives the request.");
                // This is the provider-side effect. Its successful receipt is deliberately not acknowledged.
                providerEffects.incrementAndGet();
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
            } finally {
                // No sendResponseHeaders call: the real transport sees EOF after sending the full request.
                exchange.close();
            }
        });
        server.start();
        origin = "http://localhost:" + server.getAddress().getPort();
        properties.setAllowedOrigins(List.of(origin));
        properties.setAllowPrivateHttp(true);
        properties.setEnabled(true);
        doAnswer(call -> {
            URI target = call.getArgument(0);
            assertEquals(URI.create(origin + "/send"), target);
            InetAddress[] answers = call.getArgument(1);
            assertTrue(answers.length > 0);
            for (InetAddress answer : answers) assertTrue(answer.isLoopbackAddress());
            return new InetAddress[] {ownedAddress};
        }).when(policy).validateAddresses(any(URI.class), any(InetAddress[].class));
    }

    @AfterEach
    void restoreConfigurationAndCloseOnlyOwnedResources() throws Exception {
        properties.setEnabled(previousEnabled);
        properties.setAllowPrivateHttp(previousPrivateHttp);
        properties.setAllowedOrigins(previousOrigins);
        if (server != null) server.stop(0);
        if (serverWorkers != null) {
            serverWorkers.shutdownNow();
            assertTrue(serverWorkers.awaitTermination(5, TimeUnit.SECONDS), "Owned HTTP handlers did not terminate.");
        }
        assertHandlerSucceeded();
    }

    @Test
    @Timeout(60)
    void lostWireResponsePersistsUncertaintyAndBlocksEveryResendAndManualReport() throws Exception {
        String workflow = data(post("/workflows"), json.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Lost HTTP response " + UUID.randomUUID()))).path("workflowId").asText();
        String process = data(post("/processes"), json.writeValueAsString(Map.of("workflowId", workflow,
            "processName", "HTTP"))).path("processId").asText();
        String output = data(post("/process-ios"), json.writeValueAsString(Map.of("processId", process,
            "direction", "output", "resourceType", "data", "ioName", "Response", "requiredYn", "N")))
            .path("processIoId").asText();
        JsonNode profile = data(post("/projects/" + DEMO_PROJECT + "/http-profiles"), json.writeValueAsString(Map.of(
            "name", "Owned response loss test", "baseOrigin", origin, "enabled", true,
            "operations", List.of(Map.of("operationId", "send", "method", "POST", "relativePath", "/send",
                "successStatuses", List.of(200))))));
        profileVersion.set(profile.path("profileVersionId").asText());
        data(put("/processes/" + process + "/execution-binding"), json.writeValueAsString(Map.of(
            "expectedVersion", 0, "profileVersionId", profileVersion.get(), "operationId", "send",
            "outputPortId", output, "requestMapping", Map.of("from", ""), "responseMapping", Map.of("from", ""))));
        String revision = data(post("/workflows/" + workflow + "/revisions"), null)
            .path("workflowRevisionId").asText();
        String run = data(post("/flow-runs/graph"), json.writeValueAsString(Map.of(
            "workflowId", workflow, "workflowRevisionId", revision, "runType", "actual",
            "inputPayload", Map.of("quantity", 3)))).path("flowRunId").asText();
        String step = data(get("/flow-runs/" + run + "/steps"), null).get(0).path("stepId").asText();
        String path = "/flow-runs/" + run + "/steps/" + step;
        String requestBody = json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString()));

        JsonNode first = data(post(path + "/execute"), requestBody);

        assertHandlerSucceeded();
        assertEquals(1, providerEffects.get(), "The provider accepted the request before losing its response.");
        assertEquals("OUTCOME_UNKNOWN", first.path("transportStatus").asText());
        assertEquals("PENDING", first.path("applyStatus").asText());
        assertEquals("REMOTE_OUTCOME_UNKNOWN", first.path("errorCode").asText());
        assertTrue(first.path("httpStatus").isNull());
        assertTrue(first.path("output").isNull());
        String executionId = first.path("executionId").asText();
        assertFalse(executionId.isBlank());
        assertEquals("OUTCOME_UNKNOWN", jdbc.queryForObject(
            "select transport_status from flow_run_http_execution where execution_id=?", String.class, executionId));
        assertEquals("PENDING", jdbc.queryForObject(
            "select apply_status from flow_run_http_execution where execution_id=?", String.class, executionId));
        assertNull(jdbc.queryForObject(
            "select output_json::text from flow_run_http_execution where execution_id=?", String.class, executionId));

        JsonNode repeated = data(post(path + "/execute"), requestBody);
        assertEquals(first, repeated, "An explicit retry of the same request reads the committed evidence.");
        call(post(path + "/execute"), json.writeValueAsString(Map.of("requestId", UUID.randomUUID().toString())))
            .andExpect(status().isConflict());
        call(post(path + "/complete"), "{\"attemptNo\":1,\"outputSnapshot\":{}}")
            .andExpect(status().isConflict());
        call(post(path + "/fail"), "{\"attemptNo\":1,\"errorCode\":\"BYPASS\"}")
            .andExpect(status().isConflict());
        call(post(path + "/http-executions/" + executionId + "/apply"), null)
            .andExpect(status().isConflict());

        JsonNode recovered = data(get(path + "/http-executions"), null);
        assertEquals(1, recovered.size());
        assertEquals(first, recovered.get(0), "A fresh GET must recover uncertainty from committed storage.");
        assertEquals("running", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, step));
        assertEquals("running", jdbc.queryForObject(
            "select status from flow_run_step_attempt where step_id=? and attempt_no=1", String.class, step));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from flow_run_http_execution where step_id=?", Integer.class, step));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from flow_run_step_attempt where step_id=?", Integer.class, step));
        assertEquals(1, wireRequests.get(), "Neither the HTTP client nor later API calls may retransmit.");
        assertEquals(1, providerEffects.get());
    }

    private void assertHandlerSucceeded() {
        Throwable failure = handlerFailure.get();
        if (failure != null) throw new AssertionError("Owned HTTP handler failed", failure);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        return json.readTree(call(request, body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
}
