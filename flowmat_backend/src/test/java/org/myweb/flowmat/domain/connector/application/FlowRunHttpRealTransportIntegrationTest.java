package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Only the address policy permits this test's owned loopback server; gateway, transport and persistence are real. */
@AutoConfigureMockMvc
class FlowRunHttpRealTransportIntegrationTest extends IntegrationTestSupport {
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
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> receivedHost = new AtomicReference<>();
    private final AtomicInteger transmissions = new AtomicInteger();
    private final AtomicReference<String> profileVersion = new AtomicReference<>();

    @BeforeEach
    void startOwnServer() throws Exception {
        previousEnabled = properties.isEnabled();
        previousPrivateHttp = properties.isAllowPrivateHttp();
        previousOrigins = properties.getAllowedOrigins();
        InetAddress ownedAddress = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(ownedAddress, 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/send", exchange -> {
            transmissions.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
            assertEquals("POST", exchange.getRequestMethod());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(jdbc.queryForObject("select count(*) = 1 from flow_run_http_execution where transport_status='PENDING' and profile_version_id=?",
                Boolean.class, profileVersion.get()), "The claim must commit before the real HTTP handler runs.");
            byte[] body = "{\"quantity\":9007199254740993.123456789,\"attrs\":{\"scaled\":1.2300,\"accepted\":true},\"providerPrivate\":\"unselected\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
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
    void restoreConfigurationAndStopOnlyOwnedResources() {
        properties.setEnabled(previousEnabled);
        properties.setAllowPrivateHttp(previousPrivateHttp);
        properties.setAllowedOrigins(previousOrigins);
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
    }

    @Test
    void publishedProfileActuallySendsOnceAndStoresOnlyPreciselyMappedResponse() throws Exception {
        String workflow = data(post("/workflows"), json.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Real HTTP " + UUID.randomUUID()))).path("workflowId").asText();
        String process = data(post("/processes"), json.writeValueAsString(Map.of("workflowId", workflow,
            "processName", "HTTP"))).path("processId").asText();
        String output = data(post("/process-ios"), json.writeValueAsString(Map.of("processId", process,
            "direction", "output", "resourceType", "data", "ioName", "Response", "requiredYn", "N"))).path("processIoId").asText();
        JsonNode profile = data(post("/projects/" + DEMO_PROJECT + "/http-profiles"), "{\"name\":\"Owned test HTTP\",\"baseOrigin\":\"" + origin
            + "\",\"enabled\":true,\"operations\":[{\"operationId\":\"send\",\"method\":\"POST\",\"relativePath\":\"/send\",\"successStatuses\":[200]}]}");
        profileVersion.set(profile.path("profileVersionId").asText());
        data(put("/processes/" + process + "/execution-binding"), "{\"expectedVersion\":0,\"profileVersionId\":\"" + profileVersion.get()
            + "\",\"operationId\":\"send\",\"outputPortId\":\"" + output + "\",\"requestMapping\":{\"from\":\"\"},"
            + "\"responseMapping\":{\"object\":{\"quantity\":{\"from\":\"/quantity\"},\"attrs\":{\"from\":\"/attrs\"}}}}");
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        String run = data(post("/flow-runs/graph"), "{\"workflowId\":\"" + workflow + "\",\"workflowRevisionId\":\"" + revision
            + "\",\"runType\":\"actual\",\"inputPayload\":{\"quantity\":9007199254740993.123456789,\"attrs\":{\"scaled\":1.2300}}}")
            .path("flowRunId").asText();
        String step = data(get("/flow-runs/" + run + "/steps"), null).get(0).path("stepId").asText();
        String execute = "/flow-runs/" + run + "/steps/" + step + "/execute";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";

        JsonNode result = data(post(execute), body);

        assertEquals("SUCCEEDED", result.path("transportStatus").asText());
        assertEquals("APPLIED", result.path("applyStatus").asText());
        assertEquals(new java.math.BigDecimal("9007199254740993.123456789"), result.path("output").path("quantity").decimalValue());
        assertEquals(new java.math.BigDecimal("1.2300"), result.path("output").path("attrs").path("scaled").decimalValue());
        assertFalse(result.path("output").has("providerPrivate"));
        assertTrue(received.get().contains("9007199254740993.123456789"));
        assertTrue(received.get().contains("1.2300"));
        assertEquals("localhost:" + server.getAddress().getPort(), receivedHost.get());
        String stored = jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, step);
        assertFalse(stored.contains("providerPrivate"));
        assertFalse(stored.contains("unselected"));
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.columns where table_name='flow_run_http_execution' and column_name='response_json'", Integer.class));
        assertEquals(result.path("executionId"), data(post(execute), body).path("executionId"));
        assertEquals(1, transmissions.get());
        assertEquals("completed", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, step));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
