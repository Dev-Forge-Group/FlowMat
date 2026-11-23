package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.application.FlowRunTimeoutWatcher;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Node execution policy of graph runs (docs/domain/flow-run-execution-policy.md, ADR-004 "검증") against real Postgres. */
@AutoConfigureMockMvc
class FlowRunExecutionPolicyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FlowRunTimeoutWatcher watcher;

    @Test
    void thePolicyIsValidatedVersionedAndFixedAtPublish() throws Exception {
        Fixture fixture = fixture("retry", null);
        String path = "/processes/" + fixture.target() + "/execution-policy";
        call(get(path), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.retryLimit").isEmpty());
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("timeoutSeconds", 0), Map.of("retryLimit", 11),
            Map.of("retryDelaySeconds", 86_401), Map.of("retryBackoff", "random"), Map.of("concurrencyLimit", 0),
            Map.of("maxRetryDelaySeconds", 30), Map.of("retryBackoff", "exponential", "retryDelaySeconds", 60,
                "maxRetryDelaySeconds", 30))) {
            Map<String, Object> body = new HashMap<>(bad);
            body.put("expectedVersion", 0);
            call(put(path), body).andExpect(status().isBadRequest());
        }
        call(put(path), Map.of("timeoutSeconds", 30)).andExpect(status().isBadRequest());
        Map<String, Object> policy = Map.of("retryDelaySeconds", 60, "retryBackoff", "Fixed", "concurrencyLimit", 2,
            "expectedVersion", 0);
        call(put(path), policy).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.retryBackoff").value("fixed"));
        // The same change again after a lost reply is answered; a different one from the old version is not.
        call(put(path), policy).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        call(put(path), Map.of("retryDelaySeconds", 5, "expectedVersion", 0)).andExpect(status().isConflict());

        // The revision published before the change has no policy; the next one fixes it.
        assertEquals(0, storedSnapshot(fixture.revision()).path("nodePolicies").size());
        JsonNode published = data(post("/workflows/" + fixture.workflow() + "/revisions"), null);
        JsonNode fixed = published.path("snapshot").path("nodePolicies");
        assertEquals(1, fixed.size());
        assertEquals(fixture.target(), fixed.get(0).path("processId").asText());
        assertEquals(60, fixed.get(0).path("retryDelaySeconds").asInt());

        call(put(path), new HashMap<>(Map.of("expectedVersion", 1))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.concurrencyLimit").isEmpty());
    }

    @Test
    void aDelayedRetryWaitsUntilItsRetryAtAndBacksOffToTheCap() throws Exception {
        Fixture fixture = fixture("retry", Map.of("retryDelaySeconds", 10, "retryBackoff", "exponential",
            "maxRetryDelaySeconds", 15, "expectedVersion", 0));
        String runId = startGraph(fixture);
        String target = reachTarget(runId);
        data(post(step(runId, target, "start")), null);

        JsonNode waiting = data(post(step(runId, target, "fail")), Map.of("errorCode", "BUSY"));
        assertEquals("planned", waiting.path("status").asText());
        assertEquals("BUSY", waiting.path("errorCode").asText());
        JsonNode first = attempts(runId, target).get(0);
        assertEquals(10, seconds(first.path("endedAt"), first.path("retryAt")));
        assertEquals(OffsetDateTime.parse(first.path("retryAt").asText()).toInstant(),
            OffsetDateTime.parse(waiting.path("scheduledAt").asText()).toInstant());
        assertTrue(eventTypes(runId).contains("step_retry_scheduled"));
        call(post(step(runId, target, "start")), null).andExpect(status().isConflict());
        call(put(step(runId, target, "schedule")), Map.of("scheduledAt", "2000-01-01T00:00:00Z"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("waits to retry")));

        passRetryAt(target);
        assertEquals("running", data(post(step(runId, target, "start")), null).path("status").asText());
        data(post(step(runId, target, "fail")), Map.of("errorCode", "BUSY"));
        JsonNode second = attempts(runId, target).get(1);
        assertEquals(15, seconds(second.path("endedAt"), second.path("retryAt")), "20 seconds capped to 15");
    }

    @Test
    void aRetryLimitOfZeroFailsTheRunAtTheFirstFailure() throws Exception {
        Fixture fixture = fixture("retry", Map.of("retryLimit", 0, "expectedVersion", 0));
        String runId = startGraph(fixture);
        String target = reachTarget(runId);
        data(post(step(runId, target, "start")), null);
        assertEquals("failed", data(post(step(runId, target, "fail")), Map.of("errorCode", "ONCE")).path("status").asText());
        assertEquals("failed", data(get("/flow-runs/" + runId), null).path("status").asText());
    }

    @Test
    void aTimedOutAttemptFailsOnceEvenWithTwoSweepsAndLateReportsAreRefused() throws Exception {
        Fixture fixture = fixture("retry", Map.of("timeoutSeconds", 30, "expectedVersion", 0));
        String runId = startGraph(fixture);
        String target = reachTarget(runId);
        data(post(step(runId, target, "start")), null);
        JsonNode running = attempts(runId, target).get(0);
        assertEquals(30, seconds(running.path("startedAt"), running.path("timeoutAt")));
        expire(target);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> sweeps = new ArrayList<>();
            for (int i = 0; i < 2; i++) sweeps.add(pool.submit(() -> { go.await(); watcher.sweep(); return null; }));
            go.countDown();
            for (Future<?> sweep : sweeps) sweep.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from flow_run_event where step_id = ? and event_type = 'step_timed_out'",
            Integer.class, target));
        assertEquals("system", jdbc.queryForObject(
            "select actor_type from flow_run_event where step_id = ? and event_type = 'step_failed'", String.class, target));
        JsonNode attempts = attempts(runId, target);
        assertEquals(2, attempts.size());
        assertEquals("TIMEOUT", attempts.get(0).path("errorCode").asText());
        assertEquals("running", attempts.get(1).path("status").asText());
        assertNotNull(attempts.get(1).path("timeoutAt").asText(null));

        // The old executor's report names attempt 1, which is over; the new one finishes.
        call(post(step(runId, target, "complete")), Map.of("attemptNo", 1, "outputSnapshot", Map.of()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Attempt 1 is no longer running."));
        assertEquals("completed", data(post(step(runId, target, "complete")),
            Map.of("attemptNo", 2, "outputSnapshot", Map.of())).path("status").asText());
    }

    @Test
    void aTimeoutOnAStopConnectionFailsTheRunAsTheSystem() throws Exception {
        Fixture fixture = fixture("stop", Map.of("timeoutSeconds", 60, "expectedVersion", 0));
        String runId = startGraph(fixture);
        String target = reachTarget(runId);
        data(post(step(runId, target, "start")), null);
        expire(target);
        watcher.sweep();
        assertEquals("failed", data(get("/flow-runs/" + runId), null).path("status").asText());
        assertEquals("system", jdbc.queryForObject(
            "select actor_type from flow_run_event where flow_run_id = ? and event_type = 'run_failed'", String.class, runId));
        call(post(step(runId, target, "complete")), Map.of("outputSnapshot", Map.of())).andExpect(status().isConflict());
    }

    @Test
    void theConcurrencyLimitCountsRunningStepsOfTheNodeInOneRevision() throws Exception {
        Fixture fixture = fixture("stop", Map.of("concurrencyLimit", 1, "expectedVersion", 0));
        String firstRun = startGraph(fixture);
        String first = reachTarget(firstRun);
        String secondRun = startGraph(fixture);
        String second = reachTarget(secondRun);
        data(post(step(firstRun, first, "start")), null);
        call(post(step(secondRun, second, "start")), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already has 1 running steps (limit 1)")));
        assertEquals("planned", data(get("/flow-runs/" + secondRun + "/steps"), null).get(1).path("status").asText());

        // Another revision of the same node has its own count.
        String otherRevision = data(post("/workflows/" + fixture.workflow() + "/revisions"), null).path("workflowRevisionId").asText();
        String otherRun = startGraph(new Fixture(fixture.workflow(), fixture.source(), fixture.target(), otherRevision));
        data(post(step(otherRun, reachTarget(otherRun), "start")), null);

        data(post(step(firstRun, first, "complete")), Map.of("outputSnapshot", Map.of()));
        data(post(step(secondRun, second, "start")), null);
    }

    @Test
    void onlyOneOfTwoSimultaneousStartsPassesALimitOfOne() throws Exception {
        Fixture fixture = fixture("stop", Map.of("concurrencyLimit", 1, "expectedVersion", 0));
        String firstRun = startGraph(fixture);
        String first = reachTarget(firstRun);
        String secondRun = startGraph(fixture);
        String second = reachTarget(secondRun);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> { go.await(); return startStatus(firstRun, first); });
            Future<Integer> b = pool.submit(() -> { go.await(); return startStatus(secondRun, second); });
            go.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)));
            statuses.sort(Integer::compare);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            pool.shutdownNow();
        }
    }

    private int startStatus(String runId, String stepId) throws Exception {
        return mockMvc.perform(auth(post(step(runId, stepId, "start")))).andReturn().getResponse().getStatus();
    }

    /** A workflow Source -> Target with the connection's failure policy; the target's policy is saved before publish. */
    private Fixture fixture(String failurePolicy, Map<String, Object> targetPolicy) throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Policy " + UUID.randomUUID()))
            .path("workflowId").asText();
        String source = data(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")).path("processId").asText();
        String target = data(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")).path("processId").asText();
        data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", source, "toProcessId", target,
            "failurePolicy", failurePolicy));
        if (targetPolicy != null) data(put("/processes/" + target + "/execution-policy"), targetPolicy);
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        return new Fixture(workflow, source, target, revision);
    }

    private String startGraph(Fixture fixture) throws Exception {
        return data(post("/flow-runs/graph"), Map.of("workflowId", fixture.workflow(), "workflowRevisionId", fixture.revision(),
            "runType", "test")).path("flowRunId").asText();
    }

    /** Completes the root so the target's step is created; returns that step. */
    private String reachTarget(String runId) throws Exception {
        String root = data(get("/flow-runs/" + runId + "/steps"), null).get(0).path("stepId").asText();
        data(post(step(runId, root, "start")), null);
        data(post(step(runId, root, "complete")), Map.of("outputSnapshot", Map.of("quantity", 1)));
        return data(get("/flow-runs/" + runId + "/steps"), null).get(1).path("stepId").asText();
    }

    private JsonNode storedSnapshot(String revisionId) throws Exception {
        return mapper.readTree(jdbc.queryForObject(
            "select snapshot_json::text from workflow_revision where workflow_revision_id = ?", String.class, revisionId));
    }

    private void passRetryAt(String stepId) {
        jdbc.update("update flow_run_step_attempt set retry_at = now() - interval '1 minute' where step_id = ? and retry_at is not null", stepId);
        jdbc.update("update flow_run_step set scheduled_at = now() - interval '1 minute' where step_id = ?", stepId);
    }

    private void expire(String stepId) {
        jdbc.update("update flow_run_step_attempt set started_at = now() - interval '5 minutes', timeout_at = now() - interval '1 minute'"
            + " where step_id = ? and status = 'running'", stepId);
    }

    private JsonNode attempts(String runId, String stepId) throws Exception {
        return data(get("/flow-runs/" + runId + "/steps/" + stepId + "/attempts"), null);
    }

    private List<String> eventTypes(String runId) throws Exception {
        List<String> types = new ArrayList<>();
        data(get("/flow-runs/" + runId + "/events"), null).forEach(event -> types.add(event.path("eventType").asText()));
        return types;
    }

    private static long seconds(JsonNode from, JsonNode to) {
        return Math.round(Duration.between(OffsetDateTime.parse(from.asText()), OffsetDateTime.parse(to.asText())).toMillis() / 1000.0);
    }

    private static String step(String runId, String stepId, String action) {
        return "/flow-runs/" + runId + "/steps/" + stepId + "/" + action;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mockMvc.perform(auth(request));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }

    private record Fixture(String workflow, String source, String target, String revision) {}
}
