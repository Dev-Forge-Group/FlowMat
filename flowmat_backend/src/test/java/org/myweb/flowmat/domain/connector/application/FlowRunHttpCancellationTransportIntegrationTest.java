package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cancel commits interrupt only the matching locally owned HTTP call, without promising remote rollback. */
@AutoConfigureMockMvc
@Timeout(60)
class FlowRunHttpCancellationTransportIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectorHttpProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ConnectorTargetPolicy policy;

    private final Map<String, PendingResponse> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService handlers;
    private ExecutorService callers;
    private String origin;
    private boolean previousEnabled;
    private boolean previousPrivateHttp;
    private List<String> previousOrigins;

    @BeforeEach
    void startOwnedProvider() throws Exception {
        previousEnabled = properties.isEnabled();
        previousPrivateHttp = properties.isAllowPrivateHttp();
        previousOrigins = properties.getAllowedOrigins();
        InetAddress ownedAddress = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(ownedAddress, 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        callers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try {
                PendingResponse response = pending.get(exchange.getRequestURI().getPath());
                assertNotNull(response, "Only a path owned by this test may be called.");
                response.requests.incrementAndGet();
                assertEquals("POST", exchange.getRequestMethod());
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] request = exchange.getRequestBody().readNBytes(1025);
                assertTrue(request.length <= 1024);
                assertEquals(json.readTree("{}"), json.readTree(request));
                response.received.countDown();
                assertTrue(response.release.await(30, TimeUnit.SECONDS), "Owned provider was not released.");
                byte[] body = "{\"attrs\":{\"accepted\":true}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException expectedAfterClientCancellation) {
                // Closing the local call can close the socket before this provider writes its response.
            } catch (InterruptedException stoppedDuringCleanup) {
                Thread.currentThread().interrupt();
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
            assertEquals("http", target.getScheme());
            assertEquals("localhost", target.getHost());
            assertEquals(server.getAddress().getPort(), target.getPort());
            assertTrue(pending.containsKey(target.getPath()));
            InetAddress[] answers = call.getArgument(1);
            assertTrue(answers.length > 0);
            for (InetAddress answer : answers) assertTrue(answer.isLoopbackAddress());
            return new InetAddress[] {ownedAddress};
        }).when(policy).validateAddresses(any(URI.class), any(InetAddress[].class));
    }

    @AfterEach
    void restoreAndStopOnlyOwnedResources() throws Exception {
        properties.setEnabled(previousEnabled);
        properties.setAllowPrivateHttp(previousPrivateHttp);
        properties.setAllowedOrigins(previousOrigins);
        pending.values().forEach(response -> response.release.countDown());
        if (callers != null) {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "Owned API callers did not terminate.");
        }
        if (server != null) server.stop(0);
        if (handlers != null) {
            handlers.shutdownNow();
            assertTrue(handlers.awaitTermination(5, TimeUnit.SECONDS), "Owned HTTP handlers did not terminate.");
        }
        Throwable failure = handlerFailure.get();
        if (failure != null) throw new AssertionError("Owned HTTP provider failed", failure);
    }

    @Test
    void committedCancellationStopsTheWaitingHttpCallWithoutReleasingTheProviderOrAllowingResend() throws Exception {
        Fixture fixture = fixture();
        Future<JsonNode> execution = execute(fixture);
        awaitRequest(fixture);

        assertEquals("cancelled", cancel(fixture).path("status").asText());
        JsonNode evidence = execution.get(2, TimeUnit.SECONDS);

        assertEquals(1L, fixture.provider().release.getCount(), "Cancellation must not depend on the provider responding.");
        assertCancelledEvidence(fixture, evidence);
        assertEquals(evidence, data(post(fixture.stepPath() + "/execute"), fixture.requestBody()));
        perform(post(fixture.stepPath() + "/execute"), encoded(Map.of("requestId", UUID.randomUUID().toString())))
            .andExpect(status().isConflict());
        assertEquals(1, fixture.provider().requests.get());
    }

    @Test
    void rolledBackCancellationDoesNotInterruptTheHttpCallAndTheAcceptedResponseStillApplies() throws Exception {
        Fixture fixture = fixture();
        Future<JsonNode> execution = execute(fixture);
        awaitRequest(fixture);

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            cancelInTransaction(fixture);
            transaction.setRollbackOnly();
        });

        assertThrows(TimeoutException.class, () -> execution.get(250, TimeUnit.MILLISECONDS),
            "A cancellation that rolled back must not cancel the live transport.");
        assertEquals("running", data(get("/flow-runs/" + fixture.run()), null).path("status").asText());
        fixture.provider().release.countDown();
        JsonNode evidence = execution.get(2, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", evidence.path("transportStatus").asText());
        assertEquals("APPLIED", evidence.path("applyStatus").asText());
        assertTrue(evidence.path("output").path("attrs").path("accepted").asBoolean());
        assertEquals("completed", data(get("/flow-runs/" + fixture.run() + "/steps"), null).get(0).path("status").asText());
        assertEquals(1, fixture.provider().requests.get());
    }

    @Test
    void cancellingOneRunDoesNotInterruptAnotherRunUsingTheSameTransport() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        Future<JsonNode> firstExecution = execute(first);
        Future<JsonNode> secondExecution = execute(second);
        awaitRequest(first);
        awaitRequest(second);

        cancel(first);
        assertCancelledEvidence(first, firstExecution.get(2, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> secondExecution.get(250, TimeUnit.MILLISECONDS));
        assertEquals("running", data(get("/flow-runs/" + second.run()), null).path("status").asText());
        second.provider().release.countDown();
        JsonNode secondEvidence = secondExecution.get(2, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", secondEvidence.path("transportStatus").asText());
        assertEquals("APPLIED", secondEvidence.path("applyStatus").asText());
        assertEquals(1, first.provider().requests.get());
        assertEquals(1, second.provider().requests.get());
        assertEquals(1L, first.provider().release.getCount());
    }

    @Test
    void anUncommittedCancellationDoesNotInterruptTheHttpCallUntilItsTransactionCommits() throws Exception {
        Fixture fixture = fixture();
        Future<JsonNode> execution = execute(fixture);
        awaitRequest(fixture);
        CountDownLatch cancellationStored = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> cancellation = callers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            cancelInTransaction(fixture);
            cancellationStored.countDown();
            try {
                assertTrue(commit.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Owned cancel transaction was interrupted.", failure);
            }
        }));
        try {
            assertTrue(cancellationStored.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> execution.get(250, TimeUnit.MILLISECONDS),
                "Transport cancellation must wait for the run cancellation to commit.");
        } finally {
            commit.countDown();
        }
        cancellation.get(2, TimeUnit.SECONDS);
        assertCancelledEvidence(fixture, execution.get(2, TimeUnit.SECONDS));
        assertEquals(1L, fixture.provider().release.getCount());
    }

    private void assertCancelledEvidence(Fixture fixture, JsonNode evidence) throws Exception {
        assertEquals("OUTCOME_UNKNOWN", evidence.path("transportStatus").asText(),
            "A request already received by the provider may have caused a remote effect.");
        assertEquals("LATE", evidence.path("applyStatus").asText());
        assertTrue(evidence.path("output").isNull());
        assertEquals("cancelled", data(get("/flow-runs/" + fixture.run()), null).path("status").asText());
        assertEquals("cancelled", jdbc.queryForObject("select status from flow_run_step where step_id=?", String.class, fixture.step()));
        assertEquals("cancelled", jdbc.queryForObject("select status from flow_run_step_attempt where step_id=?", String.class, fixture.step()));
        assertNull(jdbc.queryForObject("select output_snapshot::text from flow_run_step where step_id=?", String.class, fixture.step()));
        assertNull(jdbc.queryForObject("select output_json::text from flow_run_http_execution where step_id=?", String.class, fixture.step()));
        assertEquals(1, jdbc.queryForObject("select count(*) from flow_run_http_execution where step_id=?", Integer.class, fixture.step()));
    }

    private Fixture fixture() throws Exception {
        String token = UUID.randomUUID().toString();
        String providerPath = "/cancel-proof/" + token;
        PendingResponse provider = new PendingResponse();
        pending.put(providerPath, provider);
        String project = data(post("/projects"), encoded(Map.of("projectName", "HTTP cancel " + token,
            "ownerId", DEMO_OWNER))).path("projectId").asText();
        String workflow = data(post("/workflows"), encoded(Map.of("projectId", project, "workflowName", "Cancelable HTTP")))
            .path("workflowId").asText();
        String process = data(post("/processes"), encoded(Map.of("workflowId", workflow, "processName", "HTTP")))
            .path("processId").asText();
        String output = data(post("/process-ios"), encoded(Map.of("processId", process, "direction", "output",
            "resourceType", "data", "ioName", "Accepted data", "requiredYn", "N"))).path("processIoId").asText();
        JsonNode profile = data(post("/projects/" + project + "/http-profiles"), encoded(Map.of("name", "Owned provider",
            "baseOrigin", origin, "enabled", true, "operations", List.of(Map.of("operationId", "send", "method", "POST",
                "relativePath", providerPath, "successStatuses", List.of(200))))));
        data(put("/processes/" + process + "/execution-binding"), encoded(Map.of("expectedVersion", 0,
            "profileVersionId", profile.path("profileVersionId").asText(), "operationId", "send", "outputPortId", output,
            "requestMapping", Map.of("from", ""), "responseMapping", Map.of("from", ""))));
        JsonNode revision = data(post("/workflows/" + workflow + "/revisions"), null);
        String run = data(post("/flow-runs/graph"), encoded(Map.of("workflowId", workflow,
            "workflowRevisionId", revision.path("workflowRevisionId").asText(), "runType", "actual", "inputPayload", Map.of())))
            .path("flowRunId").asText();
        String step = data(get("/flow-runs/" + run + "/steps"), null).get(0).path("stepId").asText();
        for (String table : List.of("item", "inventory", "inventory_transaction", "lot_master", "bom_header", "work_order", "production_run")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, project), table);
        }
        return new Fixture(run, step, encoded(Map.of("requestId", UUID.randomUUID().toString())), provider);
    }

    private Future<JsonNode> execute(Fixture fixture) {
        return callers.submit(() -> data(post(fixture.stepPath() + "/execute"), fixture.requestBody()));
    }

    private void awaitRequest(Fixture fixture) throws Exception {
        assertTrue(fixture.provider().received.await(5, TimeUnit.SECONDS), "The owned provider never received the HTTP request.");
        assertEquals("PENDING", jdbc.queryForObject("select transport_status from flow_run_http_execution where step_id=?",
            String.class, fixture.step()));
    }

    private JsonNode cancel(Fixture fixture) throws Exception {
        return data(post("/flow-runs/" + fixture.run() + "/cancel"), "{\"reason\":\"Stop the owned HTTP test\"}");
    }

    private void cancelInTransaction(Fixture fixture) {
        try {
            assertEquals("cancelled", cancel(fixture).path("status").asText());
        } catch (Exception failure) {
            throw new AssertionError("The owned cancellation API failed.", failure);
        }
    }

    private String encoded(Object value) throws Exception { return json.writeValueAsString(value); }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        return json.readTree(perform(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String run, String step, String requestBody, PendingResponse provider) {
        String stepPath() { return "/flow-runs/" + run + "/steps/" + step; }
    }

    private static final class PendingResponse {
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
    }
}
