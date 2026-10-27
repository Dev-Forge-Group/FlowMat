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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
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

/** PO50-PO51: the order lock serializes approval decisions against the steps needed when it is acquired. */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseApprovalSequenceConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aLaterApproverWaitsForTheOrderLockThenIsRefusedWithoutSavingAnApproval() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> orderBefore = orderRow(fixture);
        List<Map<String, Object>> approvalsBefore = approvalRows(fixture);
        int notificationsBefore = notificationCount(fixture);

        Reply rejected = approveAfterGate(fixture, fixture.second(), () -> { });

        assertEquals(409, rejected.status(), rejected.message());
        assertTrue(rejected.message().contains(fixture.first()), rejected.message());
        assertEquals(orderBefore, orderRow(fixture), "A rejected later approver must not change the draft.");
        assertEquals(approvalsBefore, approvalRows(fixture), "A rejected later approver must leave no approval row.");
        assertEquals(notificationsBefore, notificationCount(fixture), "A rejected approval must not notify the next approver.");
        JsonNode saved = ok(call(fixture.owner(), get(fixture.path()), null));
        assertRequiredApprovers(saved, List.of(fixture.first(), fixture.second()));
        assertTrue(approval(saved, fixture.first()).path("approvedAt").isNull());
        assertTrue(approval(saved, fixture.second()).path("approvedAt").isNull());
        assertTrue(saved.path("approvedBy").isNull());
        assertTrue(saved.path("approvedAt").isNull());
    }

    @Test
    void aLaterApproverSucceedsWhenTheFirstApprovalCommitsBeforeItsLockIsReleased() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> firstApproval = new HashMap<>();

        Reply waiting = approveAfterGate(fixture, fixture.second(), () -> {
            JsonNode first = ok(call(fixture.first(), post(fixture.path() + "/approve"), null));
            assertFalse(approval(first, fixture.first()).path("approvedAt").isNull());
            assertTrue(approval(first, fixture.second()).path("approvedAt").isNull());
            assertTrue(first.path("approvedAt").isNull());
            firstApproval.putAll(approvalRow(fixture, fixture.first()));
        });

        // The waiting request decides after the first API transaction commits, so it is now the next approver.
        JsonNode completed = ok(waiting);
        assertEquals(fixture.second(), completed.path("approvedBy").asText());
        assertFalse(completed.path("approvedAt").isNull());
        assertRequiredApprovers(completed, List.of(fixture.first(), fixture.second()));
        assertFalse(approval(completed, fixture.second()).path("approvedAt").isNull());
        assertEquals(2, approvalRows(fixture).size());
        assertEquals(firstApproval, approvalRow(fixture, fixture.first()), "The second approval must preserve the first row and time.");
        assertEquals("ordered", ok(call(fixture.owner(), post(fixture.path() + "/order"), null)).path("status").asText());
        assertEquals(firstApproval, approvalRow(fixture, fixture.first()));
    }

    @Test
    void aWaitingApprovalUsesNewStepsAndReusesAnEarlierApprovalThatIsStillRequired() throws Exception {
        Fixture fixture = fixture();
        JsonNode partiallyApproved = ok(call(fixture.first(), post(fixture.path() + "/approve"), null));
        Instant firstTime = approvalTime(partiallyApproved, fixture.first());
        assertTrue(partiallyApproved.path("approvedAt").isNull());
        Map<String, Object> orderBefore = orderRow(fixture);
        Map<String, Object> firstApproval = approvalRow(fixture, fixture.first());
        List<Map<String, Object>> approvalsBefore = approvalRows(fixture);

        Reply rejected = approveAfterGate(fixture, fixture.second(), () -> {
            JsonNode settings = ok(call(fixture.owner(), put("/purchase-settings"), settings(fixture.project(), fixture.version(),
                List.of(step("50", fixture.added()), step("100", fixture.first()), step("500", fixture.second())))));
            assertEquals(fixture.version() + 1, settings.path("version").asLong());
            assertEquals(List.of(fixture.added(), fixture.first(), fixture.second()),
                settings.path("approvalSteps").findValuesAsText("approverId"));
        });

        assertEquals(409, rejected.status(), rejected.message());
        assertTrue(rejected.message().contains(fixture.added()), rejected.message());
        assertEquals(orderBefore, orderRow(fixture), "Changing settings must not finalize or clear the partially approved draft.");
        assertEquals(approvalsBefore, approvalRows(fixture), "A request refused by the new sequence must preserve existing approvals.");
        JsonNode current = ok(call(fixture.owner(), get(fixture.path()), null));
        assertRequiredApprovers(current, List.of(fixture.added(), fixture.first(), fixture.second()));
        assertTrue(approval(current, fixture.added()).path("approvedAt").isNull());
        assertEquals(firstTime, approvalTime(current, fixture.first()));
        assertTrue(approval(current, fixture.second()).path("approvedAt").isNull());
        assertTrue(current.path("approvedBy").isNull());
        assertTrue(current.path("approvedAt").isNull());
        assertEquals(409, call(fixture.owner(), post(fixture.path() + "/order"), null).status());

        JsonNode added = ok(call(fixture.added(), post(fixture.path() + "/approve"), null));
        assertTrue(added.path("approvedAt").isNull(), "The original second approver is still required.");
        assertEquals(firstTime, approvalTime(added, fixture.first()));
        // No new request from the original first approver: that person's original approval still counts.
        JsonNode completed = ok(call(fixture.second(), post(fixture.path() + "/approve"), null));
        assertEquals(fixture.second(), completed.path("approvedBy").asText());
        assertFalse(completed.path("approvedAt").isNull());
        assertEquals(firstTime, approvalTime(completed, fixture.first()));
        assertEquals(firstApproval, approvalRow(fixture, fixture.first()));
        assertEquals(3, approvalRows(fixture).size());
        assertEquals("ordered", ok(call(fixture.owner(), post(fixture.path() + "/order"), null)).path("status").asText());
        assertEquals(firstApproval, approvalRow(fixture, fixture.first()));
    }

    /** The request is proved blocked before another real API operation runs in the transaction holding its order lock. */
    private Reply approveAfterGate(Fixture fixture, String actor, WhileLocked change) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Future<Reply> pending = gate.execute(transaction -> {
                jdbc.queryForObject("select purchase_order_id from purchase_order where purchase_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(actor, post(fixture.path() + "/approve"), null));
                awaitOrderLock(waiting, blocker);
                try {
                    change.run();
                } catch (Exception exception) {
                    throw new IllegalStateException("The operation while the approval waited failed.", exception);
                }
                return waiting;
            });
            assertNotNull(pending);
            return pending.get(20, TimeUnit.SECONDS);
        } finally {
            // TransactionTemplate has committed or rolled back, releasing the order lock, before the request is joined.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The waiting approval request did not terminate.");
        }
    }

    private void awaitOrderLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The approval request must wait for the held purchase order row lock.");
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity"
                + " where datname=current_database() and wait_event_type='Lock' and ? = any(pg_blocking_pids(pid)))",
                Boolean.class, blocker))) {
                return;
            }
            try {
                // A polling interval only; PostgreSQL's blocking graph supplies the evidence of the wait.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the approval's order lock.", exception);
            }
        }
        fail("The approval request did not reach the held purchase order row lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String first = user();
        String second = user();
        String added = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "Approval sequence " + tag, "ownerId", owner)))
            .path("projectId").asText();
        for (String editor : List.of(first, second, added)) {
            assertEquals(1, jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status)"
                + " values(?,?,?,'editor','active')", UUID.randomUUID().toString(), project, editor));
        }
        JsonNode saved = ok(call(owner, put("/purchase-settings"), settings(project, null,
            List.of(step("100", first), step("500", second)))));
        assertEquals(List.of(first, second), saved.path("approvalSteps").findValuesAsText("approverId"));
        assertTrue(saved.path("version").isIntegralNumber());
        String item = ok(call(owner, post("/items"), Map.of("projectId", project, "itemCode", "SEQ-" + tag,
            "itemName", "Sequence approval material", "itemType", "material", "unitId", "unit_kg"))).path("itemId").asText();
        JsonNode draft = ok(call(owner, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10, "unitPrice", new BigDecimal("60"))))));
        assertEquals("draft", draft.path("status").asText());
        assertTrue(draft.path("needsApproval").asBoolean());
        assertTrue(draft.path("approvedBy").isNull());
        assertTrue(draft.path("approvedAt").isNull());
        assertRequiredApprovers(draft, List.of(first, second));
        assertTrue(approval(draft, first).path("approvedAt").isNull());
        assertTrue(approval(draft, second).path("approvedAt").isNull());
        Fixture fixture = new Fixture(project, draft.path("purchaseOrderId").asText(), owner, first, second, added,
            saved.path("version").asLong());
        assertTrue(approvalRows(fixture).isEmpty());
        return fixture;
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER));
        return id;
    }

    private static Map<String, Object> settings(String project, Long version, List<Map<String, Object>> steps) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", project);
        body.put("approvalLimit", new BigDecimal("1000"));
        body.put("expectedVersion", version);
        body.put("approvalSteps", steps);
        return body;
    }

    private static Map<String, Object> step(String fromValue, String approver) {
        return Map.of("fromValue", new BigDecimal(fromValue), "approverId", approver);
    }

    private Map<String, Object> orderRow(Fixture fixture) {
        return jdbc.queryForMap("select * from purchase_order where purchase_order_id=?", fixture.order());
    }

    private List<Map<String, Object>> approvalRows(Fixture fixture) {
        return jdbc.queryForList("select * from purchase_order_approval where purchase_order_id=? order by approver_id", fixture.order());
    }

    private Map<String, Object> approvalRow(Fixture fixture, String actor) {
        return jdbc.queryForMap("select * from purchase_order_approval where purchase_order_id=? and approver_id=?", fixture.order(), actor);
    }

    private int notificationCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from notification where project_id=? and ref_id=?", Integer.class,
            fixture.project(), fixture.order());
    }

    private static void assertRequiredApprovers(JsonNode order, List<String> expected) {
        assertEquals(expected, order.path("approvals").findValuesAsText("approverId"));
    }

    private static JsonNode approval(JsonNode order, String actor) {
        for (JsonNode approval : order.path("approvals")) {
            if (actor.equals(approval.path("approverId").asText())) return approval;
        }
        throw new AssertionError("No approval step for " + actor + ": " + order);
    }

    private static Instant approvalTime(JsonNode order, String actor) {
        JsonNode at = approval(order, actor).path("approvedAt");
        assertTrue(at.isTextual(), order.toString());
        return OffsetDateTime.parse(at.asText()).toInstant();
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

    @FunctionalInterface
    private interface WhileLocked {
        void run() throws Exception;
    }

    private record Fixture(String project, String order, String owner, String first, String second, String added, long version) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
