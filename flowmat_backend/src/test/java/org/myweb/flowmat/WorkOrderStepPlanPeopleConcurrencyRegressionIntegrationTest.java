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
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Concurrent replacements leave one complete process plan and its people, serialized by the work order row lock. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Timeout(60)
class WorkOrderStepPlanPeopleConcurrencyRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aWaitingReplacementLeavesOnlyItsCompletePlanAndPeople() throws Exception {
        Fixture fixture = fixture();
        StoredRows original = storedRows(fixture);
        assertEquals(2, original.plans().size());
        assertEquals(4, original.people().size());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            GatedSave saves = gate.execute(transaction -> {
                jdbc.queryForObject("select work_order_id from work_order where work_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(fixture.owner(), put(fixture.planPath()),
                    plan(fixture.secondNode(), fixture.member(), 41, fixture.owner(), 109)));
                awaitOrderLock(waiting, blocker);
                try {
                    // This first replacement joins the gate transaction; the other request is already blocked in Postgres.
                    assertPlan(ok(call(fixture.owner(), put(fixture.planPath()),
                        plan(fixture.firstNode(), fixture.owner(), 73, fixture.member(), 17))),
                        fixture.firstNode(), fixture.owner(), 73, fixture.member(), 17);
                    StoredRows first = storedRows(fixture);
                    assertStoredPlan(first, fixture, fixture.firstNode(), fixture.owner(), 73, fixture.member(), 17);
                    assertRowsGone(original);
                    return new GatedSave(waiting, first);
                } catch (Exception exception) {
                    throw new IllegalStateException("Saving the first isolated people plan failed.", exception);
                }
            });
            assertNotNull(saves);
            // TransactionTemplate has committed the gate before its result is returned; never join the worker under that lock.
            Reply last = saves.waiting().get(20, TimeUnit.SECONDS);
            assertPlan(ok(last), fixture.secondNode(), fixture.member(), 41, fixture.owner(), 109);
            assertPlan(ok(call(fixture.owner(), get(fixture.path() + "/labor-comparison"), null)),
                fixture.secondNode(), fixture.member(), 41, fixture.owner(), 109);
            assertStoredPlan(storedRows(fixture), fixture, fixture.secondNode(), fixture.member(), 41, fixture.owner(), 109);
            assertRowsGone(original);
            assertRowsGone(saves.first());
        } finally {
            // The gate has committed or rolled back before shutdown or waiting for this executor.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The competing people-plan save did not terminate.");
        }
    }

    private void awaitOrderLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The second save must wait for the held work order row lock.");
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                    Boolean.class, blocker))) {
                return;
            }
            try {
                // This is only a polling interval. The pg_blocking_pids result, not elapsed time, proves contention.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the work order lock.", exception);
            }
        }
        fail("The second people-plan save did not reach the held work order row lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String member = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "People-plan race " + tag,
            "ownerId", owner))).path("projectId").asText();
        String token = ok(call(owner, post("/project-invites"), Map.of("projectId", project,
            "invitedEmail", member + "@test.local", "projectRole", "editor"))).path("inviteToken").asText();
        assertEquals(member, ok(call(member, post("/project-invites/accept"), Map.of("inviteToken", token)))
            .path("userId").asText());
        String workflow = ok(call(owner, post("/workflows"), Map.of("projectId", project,
            "workflowName", "People-plan race " + tag))).path("workflowId").asText();
        String firstNode = process(owner, workflow, "First process");
        String secondNode = process(owner, workflow, "Second process");
        String order = ok(call(owner, post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
            "workOrderTitle", "People-plan race " + tag, "targetQuantity", 1))).path("workOrderId").asText();
        Fixture fixture = new Fixture(owner, member, firstNode, secondNode, order);
        JsonNode initial = ok(call(owner, put(fixture.planPath()), Map.of("steps", List.of(
            step(firstNode, owner, 20, member, 10), step(secondNode, member, 40, owner, 15)))));
        assertEquals(85, initial.path("stepPlannedMinutes").asInt());
        assertEquals(2, initial.path("steps").size());
        return fixture;
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            id, id + "@test.local", DEMO_OWNER));
        return id;
    }

    private String process(String owner, String workflow, String name) throws Exception {
        return ok(call(owner, post("/processes"), Map.of("workflowId", workflow, "processName", name,
            "nodeType", "process"))).path("processId").asText();
    }

    private static Map<String, Object> plan(String node, String firstUser, int firstMinutes, String secondUser, int secondMinutes) {
        return Map.of("steps", List.of(step(node, firstUser, firstMinutes, secondUser, secondMinutes)));
    }

    private static Map<String, Object> step(String node, String firstUser, int firstMinutes, String secondUser, int secondMinutes) {
        return Map.of("nodeId", node, "people", List.of(
            Map.of("userId", firstUser, "plannedMinutes", firstMinutes),
            Map.of("userId", secondUser, "plannedMinutes", secondMinutes)));
    }

    private static void assertPlan(JsonNode comparison, String node, String firstUser, int firstMinutes,
                                   String secondUser, int secondMinutes) {
        assertEquals(firstMinutes + secondMinutes, comparison.path("stepPlannedMinutes").asInt());
        assertEquals(0, comparison.path("actualMinutes").asInt());
        assertEquals(1, comparison.path("steps").size());
        JsonNode step = comparison.path("steps").get(0);
        assertEquals(node, step.path("nodeId").asText());
        assertEquals(firstMinutes + secondMinutes, step.path("plannedMinutes").asInt());
        assertEquals(0, step.path("actualMinutes").asInt());
        assertEquals(2, step.path("people").size());
        assertPerson(step.path("people").get(0), firstUser, firstMinutes);
        assertPerson(step.path("people").get(1), secondUser, secondMinutes);
    }

    private static void assertPerson(JsonNode person, String user, int minutes) {
        assertEquals(user, person.path("userId").asText());
        assertEquals(minutes, person.path("plannedMinutes").asInt());
        assertEquals(0, person.path("actualMinutes").asInt());
    }

    private StoredRows storedRows(Fixture fixture) {
        List<Map<String, Object>> plans = jdbc.queryForList(
            "select plan_id,node_id,sort_order,planned_minutes,updated_by from work_order_step_plan"
                + " where work_order_id=? order by sort_order", fixture.order());
        List<Map<String, Object>> people = jdbc.queryForList(
            "select person.plan_person_id,person.plan_id,person.user_id,person.sort_order,person.planned_minutes"
                + " from work_order_step_plan_person person join work_order_step_plan plan on plan.plan_id=person.plan_id"
                + " where plan.work_order_id=? order by plan.sort_order,person.sort_order", fixture.order());
        return new StoredRows(plans, people);
    }

    private static void assertStoredPlan(StoredRows rows, Fixture fixture, String node, String firstUser, int firstMinutes,
                                         String secondUser, int secondMinutes) {
        assertEquals(1, rows.plans().size());
        Map<String, Object> plan = rows.plans().get(0);
        assertEquals(node, plan.get("node_id"));
        assertEquals(0, ((Number) plan.get("sort_order")).intValue());
        assertEquals(firstMinutes + secondMinutes, ((Number) plan.get("planned_minutes")).intValue());
        assertEquals(fixture.owner(), plan.get("updated_by"));
        assertEquals(2, rows.people().size());
        assertStoredPerson(rows.people().get(0), plan.get("plan_id"), firstUser, 0, firstMinutes);
        assertStoredPerson(rows.people().get(1), plan.get("plan_id"), secondUser, 1, secondMinutes);
    }

    private static void assertStoredPerson(Map<String, Object> person, Object planId, String user, int order, int minutes) {
        assertEquals(planId, person.get("plan_id"));
        assertEquals(user, person.get("user_id"));
        assertEquals(order, ((Number) person.get("sort_order")).intValue());
        assertEquals(minutes, ((Number) person.get("planned_minutes")).intValue());
    }

    private void assertRowsGone(StoredRows old) {
        for (Map<String, Object> row : old.plans()) {
            assertEquals(0L, jdbc.queryForObject("select count(*) from work_order_step_plan where plan_id=?", Long.class,
                row.get("plan_id")), "A superseded plan row must be removed.");
        }
        for (Map<String, Object> row : old.people()) {
            assertEquals(0L, jdbc.queryForObject("select count(*) from work_order_step_plan_person where plan_person_id=?", Long.class,
                row.get("plan_person_id")), "A superseded person's row must be removed with its plan.");
        }
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

    private record Fixture(String owner, String member, String firstNode, String secondNode, String order) {
        String path() { return "/work-orders/" + order; }
        String planPath() { return path() + "/step-plan"; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }

    private record StoredRows(List<Map<String, Object>> plans, List<Map<String, Object>> people) {
    }

    private record GatedSave(Future<Reply> waiting, StoredRows first) {
    }
}
