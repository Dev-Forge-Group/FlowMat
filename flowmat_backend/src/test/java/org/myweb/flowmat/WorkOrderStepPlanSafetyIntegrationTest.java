package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@Timeout(60)
class WorkOrderStepPlanSafetyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aWaitingStepPlanIsRefusedAfterCancellationWithoutChangingTheStoredPlan() throws Exception {
        Fixture fixture = fixture();
        List<Map<String, Object>> before = storedPlan(fixture);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Future<Reply> pending = gate.execute(transaction -> {
                jdbc.queryForObject("select work_order_id from work_order where work_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(fixture.user(), put(fixture.planPath()), plan(fixture.node(), 99)));
                awaitOrderLock(waiting, blocker);
                try {
                    // Cancellation joins this transaction and uses the actual business path while the competing save waits.
                    JsonNode cancelled = ok(call(fixture.user(), post(fixture.path() + "/cancel"), null));
                    assertEquals("cancelled", cancelled.path("workOrderStatus").asText());
                } catch (Exception exception) {
                    throw new IllegalStateException("Cancelling the isolated work order failed.", exception);
                }
                return waiting;
            });
            assertNotNull(pending);
            Reply rejected = pending.get(20, TimeUnit.SECONDS);
            assertEquals(409, rejected.status(), rejected.message());
            assertTrue(rejected.message().contains("cancelled"), rejected.message());
            assertEquals(before, storedPlan(fixture), "A save refused after cancellation must preserve every stored plan field.");
            assertEquals("cancelled", ok(call(fixture.user(), get(fixture.path()), null)).path("workOrderStatus").asText());
            assertPlan(ok(call(fixture.user(), get(fixture.path() + "/labor-comparison"), null)), fixture.node(), 60);
        } finally {
            // The transaction callback has already committed or rolled back, so no worker is joined while its gate is held.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The waiting step-plan save did not terminate.");
        }
    }

    @Test
    void anOldWorkflowsProcessIsRefusedAfterTheDraftChangesWorkflowButTheNewProcessCanBePlanned() throws Exception {
        Fixture fixture = fixture();
        String nextWorkflow = workflow(fixture.user(), fixture.project(), "Replacement workflow");
        String nextNode = process(fixture.user(), nextWorkflow, "Replacement step");
        JsonNode changed = ok(call(fixture.user(), put(fixture.path()),
            Map.of("workflowId", nextWorkflow, "targetQuantity", 1)));
        assertEquals("draft", changed.path("workOrderStatus").asText());
        assertEquals(nextWorkflow, changed.path("workflowId").asText());
        List<Map<String, Object>> beforeRejectedSave = storedPlan(fixture);

        Reply rejected = call(fixture.user(), put(fixture.planPath()), plan(fixture.node(), 99));

        assertEquals(400, rejected.status(), rejected.message());
        assertTrue(rejected.message().contains(fixture.node()), rejected.message());
        assertTrue(rejected.message().contains("not a process"), rejected.message());
        assertEquals(beforeRejectedSave, storedPlan(fixture), "Rejecting an old process must not partially replace the plan.");

        assertPlan(ok(call(fixture.user(), put(fixture.planPath()), plan(nextNode, 90))), nextNode, 90);
        assertPlan(ok(call(fixture.user(), get(fixture.path() + "/labor-comparison"), null)), nextNode, 90);
        List<Map<String, Object>> saved = storedPlan(fixture);
        assertEquals(1, saved.size());
        assertEquals(nextNode, saved.getFirst().get("node_id"));
        assertEquals(90, ((Number) saved.getFirst().get("planned_minutes")).intValue());
    }

    private void awaitOrderLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The step-plan save must wait for the work order's held row lock.");
            // This observer shares the gate transaction; discard cached activity before looking for the newly started worker.
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))", Boolean.class, blocker))) {
                return;
            }
            try {
                // A polling interval only: pg_blocking_pids, rather than elapsed time, proves contention.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the work order lock.", exception);
            }
        }
        fail("The step-plan save did not reach the held work order row lock.");
    }

    private List<Map<String, Object>> storedPlan(Fixture fixture) {
        return jdbc.queryForList("select plan_id,work_order_id,node_id,node_name,sort_order,planned_minutes,updated_by,updated_at"
            + " from work_order_step_plan where work_order_id=? order by sort_order", fixture.order());
    }

    private Fixture fixture() throws Exception {
        String user = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            user, user + "@test.local", DEMO_OWNER));
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(user, post("/projects"), Map.of("projectName", "Step plan safety " + tag, "ownerId", user)))
            .path("projectId").asText();
        String workflow = workflow(user, project, "Original workflow " + tag);
        String node = process(user, workflow, "Original step");
        String order = ok(call(user, post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
            "workOrderTitle", "Step plan safety " + tag, "targetQuantity", 1))).path("workOrderId").asText();
        Fixture fixture = new Fixture(user, project, node, order);
        assertPlan(ok(call(user, put(fixture.planPath()), plan(node, 60))), node, 60);
        assertEquals(1, storedPlan(fixture).size());
        return fixture;
    }

    private String workflow(String user, String project, String name) throws Exception {
        return ok(call(user, post("/workflows"), Map.of("projectId", project, "workflowName", name)))
            .path("workflowId").asText();
    }

    private String process(String user, String workflow, String name) throws Exception {
        return ok(call(user, post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process")))
            .path("processId").asText();
    }

    private static Map<String, Object> plan(String node, int minutes) {
        return Map.of("steps", List.of(Map.of("nodeId", node, "plannedMinutes", minutes)));
    }

    private static void assertPlan(JsonNode comparison, String node, int minutes) {
        assertEquals(minutes, comparison.path("stepPlannedMinutes").asInt());
        assertEquals(1, comparison.path("steps").size());
        JsonNode step = comparison.path("steps").get(0);
        assertEquals(node, step.path("nodeId").asText());
        assertEquals(minutes, step.path("plannedMinutes").asInt());
        assertEquals(0, step.path("actualMinutes").asInt());
    }

    private Reply call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private record Fixture(String user, String project, String node, String order) {
        String path() { return "/work-orders/" + order; }
        String planPath() { return path() + "/step-plan"; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
