package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real HTTP execution routes its published mapped output into a second graph step. */
@AutoConfigureMockMvc
class FlowRunHttpMultiStepTransportIntegrationTest extends IntegrationTestSupport {
    private static final String INPUT = "{\"attrs\":{\"requested\":9007199254740993.123456789,"
        + "\"scale\":1.2300,\"day\":\"2026-10-27\",\"optional\":null}}";
    private static final String REMOTE_RESPONSE = "{\"result\":{\"amount\":9007199254740993.123456789,"
        + "\"scaled\":1.2300,\"day\":\"2026-10-27\",\"optional\":null,\"accepted\":true},"
        + "\"providerPrivate\":\"not-selected\"}";
    private static final Map<String, Object> DATA_SCHEMA = Map.of("type", "object",
        "properties", Map.of("amount", Map.of("type", "number"), "scaled", Map.of("type", "number"),
            "day", Map.of("type", "string"), "accepted", Map.of("type", "boolean")),
        "required", List.of("amount", "scaled", "day", "accepted"));
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
    private final AtomicReference<String> profileVersion = new AtomicReference<>();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

    @BeforeEach
    void startOwnedProvider() throws Exception {
        previousEnabled = properties.isEnabled();
        previousPrivateHttp = properties.isAllowPrivateHttp();
        previousOrigins = properties.getAllowedOrigins();
        InetAddress ownedAddress = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(ownedAddress, 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/", exchange -> {
            wireRequests.incrementAndGet();
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("/send-original", exchange.getRequestURI().toString(),
                    "The later profile revision must not change this published request.");
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = exchange.getRequestBody().readNBytes(1025);
                assertTrue(bytes.length <= 1024);
                JsonNode request = exact().readTree(new String(bytes, StandardCharsets.UTF_8));
                assertEquals(exact().readTree(INPUT), request, "The edited draft request mapping must not be used.");
                assertEquals(new BigDecimal("9007199254740993.123456789"), request.path("attrs").path("requested").decimalValue());
                assertEquals(new BigDecimal("1.2300"), request.path("attrs").path("scale").decimalValue());
                assertTrue(jdbc.queryForObject("select count(*) = 1 from flow_run_http_execution "
                        + "where transport_status='PENDING' and profile_version_id=?",
                    Boolean.class, profileVersion.get()), "The claim must commit before the provider receives the request.");
                byte[] response = REMOTE_RESPONSE.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
            } finally {
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
            assertEquals(URI.create(origin + "/send-original"), target);
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
    void pinnedHttpResultRoutesExactDataAndCompletesTheGraphWithoutManufacturingRowsOrDuplicateSends() throws Exception {
        String project = data(post("/projects"), encoded(Map.of("projectName", "HTTP data flow " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER))).path("projectId").asText();
        String workflow = data(post("/workflows"), encoded(Map.of("projectId", project, "workflowName", "Receive and consume")))
            .path("workflowId").asText();
        String source = process(workflow, "Receive HTTP data");
        String sink = process(workflow, "Consume accepted data");
        String sourceOutput = port(source, "output");
        String sinkInput = port(sink, "input");
        String connection = data(post("/process-connections"), encoded(Map.of("workflowId", workflow,
            "fromProcessId", source, "toProcessId", sink, "fromIoId", sourceOutput, "toIoId", sinkInput)))
            .path("connectionId").asText();
        String profilePath = "/projects/" + project + "/http-profiles";
        JsonNode profile = data(post(profilePath), encoded(Map.of("name", "Published provider", "baseOrigin", origin,
            "enabled", true, "operations", List.of(Map.of("operationId", "send", "method", "POST",
                "relativePath", "/send-original", "successStatuses", List.of(200))))));
        profileVersion.set(profile.path("profileVersionId").asText());
        String bindingPath = "/processes/" + source + "/execution-binding";
        data(put(bindingPath), encoded(Map.of("expectedVersion", 0, "profileVersionId", profileVersion.get(),
            "operationId", "send", "outputPortId", sourceOutput, "requestMapping", Map.of("from", ""),
            "responseMapping", Map.of("object", Map.of("attrs", Map.of("from", "/result"))))));
        JsonNode revision = data(post("/workflows/" + workflow + "/revisions"), null);
        assertEquals(4, revision.path("schemaVersion").asInt());
        String revisionId = revision.path("workflowRevisionId").asText();
        JsonNode frozenBindings = revision.path("snapshot").path("nodeBindings").deepCopy();

        // Both mutable sources change after publication. The run must still use the old profile version and mappings.
        JsonNode updatedProfile = data(put(profilePath + "/" + profile.path("profileId").asText()), encoded(Map.of(
            "expectedVersion", 0, "name", "Edited provider", "baseOrigin", origin, "enabled", true,
            "operations", List.of(Map.of("operationId", "send", "method", "POST", "relativePath", "/send-edited",
                "successStatuses", List.of(200))))));
        String updatedVersion = updatedProfile.path("profileVersionId").asText();
        assertNotEquals(profileVersion.get(), updatedVersion);
        data(put(bindingPath), encoded(Map.of("expectedVersion", 1, "profileVersionId", updatedVersion,
            "operationId", "send", "outputPortId", sourceOutput,
            "requestMapping", Map.of("literal", Map.of("editedDraft", true)),
            "responseMapping", Map.of("literal", Map.of("attrs", Map.of("editedDraft", true))))));
        assertEquals(updatedVersion, data(get(bindingPath), null).path("profileVersionId").asText());
        assertEquals(frozenBindings, data(get("/workflows/" + workflow + "/revisions/" + revisionId), null)
            .path("snapshot").path("nodeBindings"));

        String run = data(post("/flow-runs/graph"), "{\"workflowId\":" + encoded(workflow)
            + ",\"workflowRevisionId\":" + encoded(revisionId) + ",\"runType\":\"actual\",\"inputPayload\":" + INPUT + "}")
            .path("flowRunId").asText();
        JsonNode initialSteps = steps(run);
        assertEquals(1, initialSteps.size());
        assertEquals(source, initialSteps.get(0).path("nodeId").asText());
        String sourceStep = initialSteps.get(0).path("stepId").asText();
        String executePath = "/flow-runs/" + run + "/steps/" + sourceStep + "/execute";
        String request = encoded(Map.of("requestId", UUID.randomUUID().toString()));
        JsonNode evidence = data(post(executePath), request);
        assertHandlerSucceeded();
        assertEquals("SUCCEEDED", evidence.path("transportStatus").asText());
        assertEquals("APPLIED", evidence.path("applyStatus").asText());
        assertMappedOutput(evidence.path("output"));
        assertFalse(evidence.toString().contains("providerPrivate"));
        assertFalse(evidence.toString().contains("not-selected"));
        assertEquals(evidence, data(post(executePath), request));

        JsonNode afterHttp = steps(run);
        assertEquals(2, afterHttp.size());
        JsonNode completedSource = findNode(afterHttp, source);
        assertEquals("completed", completedSource.path("status").asText());
        assertMappedOutput(completedSource.path("outputSnapshot"));
        JsonNode next = findNode(afterHttp, sink);
        assertEquals("planned", next.path("status").asText());
        assertEquals(sourceStep, next.path("sourceStepId").asText());
        assertEquals(connection, next.path("sourceConnectionId").asText());
        assertMappedOutput(next.path("inputSnapshot"));
        String sinkStep = next.path("stepId").asText();
        String sinkPath = "/flow-runs/" + run + "/steps/" + sinkStep;
        data(post(sinkPath + "/start"), null);
        data(post(sinkPath + "/complete"), "{\"attemptNo\":1,\"outputSnapshot\":{\"attrs\":{\"stored\":true}}}");
        assertEquals("finished", data(post("/flow-runs/" + run + "/finish"), "{}").path("status").asText());
        assertEquals("finished", data(get("/flow-runs/" + run), null).path("status").asText());
        assertEquals(evidence, data(post(executePath), request), "Replaying a known request after finishing must not transmit.");
        assertEquals(2, steps(run).size());
        for (JsonNode step : steps(run)) {
            assertEquals("completed", step.path("status").asText());
            assertEquals(1, data(get("/flow-runs/" + run + "/steps/" + step.path("stepId").asText() + "/attempts"), null).size());
        }
        assertEquals(1, wireRequests.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from flow_run_http_execution where flow_run_id=?", Integer.class, run));
        assertEquals(profileVersion.get(), jdbc.queryForObject(
            "select profile_version_id from flow_run_http_execution where flow_run_id=?", String.class, run));
        String stored = jdbc.queryForObject("select output_json::text from flow_run_http_execution where flow_run_id=?", String.class, run);
        assertMappedOutput(exact().readTree(stored));
        assertFalse(stored.contains("providerPrivate"));
        assertFalse(stored.contains("not-selected"));
        for (String table : List.of("item", "inventory", "inventory_transaction", "lot_master", "bom_header", "work_order", "production_run")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, project), table);
        }
    }

    private void assertMappedOutput(JsonNode output) {
        assertEquals(1, output.size());
        JsonNode attrs = output.path("attrs");
        assertEquals(5, attrs.size());
        assertEquals(new BigDecimal("9007199254740993.123456789"), attrs.path("amount").decimalValue());
        assertEquals(new BigDecimal("1.2300"), attrs.path("scaled").decimalValue());
        assertEquals("2026-10-27", attrs.path("day").asText());
        assertTrue(attrs.path("optional").isNull());
        assertTrue(attrs.path("accepted").asBoolean());
    }

    private JsonNode findNode(JsonNode steps, String node) {
        for (JsonNode step : steps) if (node.equals(step.path("nodeId").asText())) return step;
        throw new AssertionError("The graph did not create the expected node's step.");
    }

    private String process(String workflow, String name) throws Exception {
        return data(post("/processes"), encoded(Map.of("workflowId", workflow, "processName", name))).path("processId").asText();
    }

    private String port(String process, String direction) throws Exception {
        return data(post("/process-ios"), encoded(Map.of("processId", process, "direction", direction,
            "resourceType", "data", "ioName", "Accepted data " + direction, "requiredYn", "Y", "quantity", 0,
            "unit", "ea", "schemaJson", DATA_SCHEMA, "validationRule", "attrs.accepted = true")))
            .path("processIoId").asText();
    }

    private JsonNode steps(String run) throws Exception {
        return data(get("/flow-runs/" + run + "/steps"), null);
    }

    private ObjectReader exact() {
        return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    private String encoded(Object value) throws Exception { return json.writeValueAsString(value); }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return exact().readTree(mvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private void assertHandlerSucceeded() {
        Throwable failure = handlerFailure.get();
        if (failure != null) throw new AssertionError("Owned HTTP handler failed", failure);
    }
}
