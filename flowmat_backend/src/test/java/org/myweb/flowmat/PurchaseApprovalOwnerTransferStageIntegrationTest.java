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

/** Rechecks a waiting former owner's access when a draft already has a member-stage approval (WORKBOARD 2hb, request 4). */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseApprovalOwnerTransferStageIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aWaitingFormerOwnerCannotChangeAPartiallyApprovedDraftAfterLosingMembership() throws Exception {
        Fixture fixture = fixture();
        JsonNode partial = ok(call(fixture.member(), post(fixture.path() + "/approve"), null));
        assertEquals(List.of(fixture.member(), fixture.owner()), partial.path("approvals").findValuesAsText("approverId"));
        assertFalse(partial.path("approvals").get(0).path("approvedAt").isNull());
        assertTrue(partial.path("approvals").get(1).path("approvedAt").isNull());
        assertTrue(partial.path("approvedBy").isNull());
        assertTrue(partial.path("approvedAt").isNull());
        Map<String, Object> orderBefore = orderRow(fixture);
        List<Map<String, Object>> approvalsBefore = approvalRows(fixture);
        assertEquals(1, approvalsBefore.size());
        assertEquals(fixture.member(), approvalsBefore.get(0).get("approver_id"));
        int notificationsBefore = notificationCount(fixture);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Future<Reply> pending = gate.execute(transaction -> {
                jdbc.queryForObject("select purchase_order_id from purchase_order where purchase_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(fixture.owner(), post(fixture.path() + "/approve"), null));
                awaitOrderLock(waiting, blocker);

                // There is no owner-transfer API. Only this new fixture is changed atomically to represent the new access state.
                assertEquals(1, jdbc.update("update project set owner_id=? where project_id=?", fixture.successor(), fixture.project()));
                assertEquals(1, jdbc.update("update project_member set project_role='viewer',member_status='removed'"
                    + " where project_id=? and user_id=?", fixture.project(), fixture.owner()));
                assertEquals(1, jdbc.update("update project_member set project_role='owner' where project_id=? and user_id=?"
                    + " and member_status='active'", fixture.project(), fixture.successor()));
                return waiting;
            });
            assertNotNull(pending);
            Reply refused = pending.get(20, TimeUnit.SECONDS);
            assertEquals(403, refused.status(), refused.message());
        } finally {
            // The transaction releases its lock before normal or exceptional worker cleanup.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The former owner's waiting approval did not terminate.");
        }

        assertEquals(fixture.successor(), jdbc.queryForObject("select owner_id from project where project_id=?",
            String.class, fixture.project()));
        assertEquals(1, jdbc.queryForObject("select count(*) from project_member where project_id=? and user_id=?"
            + " and project_role='viewer' and member_status='removed'", Integer.class, fixture.project(), fixture.owner()));
        assertEquals(orderBefore, orderRow(fixture), "Refusing the former owner must preserve every stored order field.");
        assertEquals(approvalsBefore, approvalRows(fixture), "The refused request must preserve the member's approval row and timestamp.");
        assertEquals(notificationsBefore, notificationCount(fixture), "A refused former owner must not create an approval notification.");
        JsonNode saved = ok(call(fixture.member(), get(fixture.path()), null));
        assertEquals("draft", saved.path("status").asText());
        assertTrue(saved.path("approvedBy").isNull());
        assertTrue(saved.path("approvedAt").isNull());
    }

    private void awaitOrderLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The former owner's approval must wait on the held purchase order row lock.");
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity"
                + " where datname=current_database() and wait_event_type='Lock' and ? = any(pg_blocking_pids(pid)))",
                Boolean.class, blocker))) {
                return;
            }
            try {
                // Polling only: PostgreSQL's blocking graph proves that the approval reached the held lock.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the former owner's approval lock.", exception);
            }
        }
        fail("The former owner's approval did not reach the held purchase order row lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String member = user();
        String successor = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "Owner stage safety " + tag, "ownerId", owner)))
            .path("projectId").asText();
        for (String editor : List.of(member, successor)) {
            assertEquals(1, jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status)"
                + " values(?,?,?,'editor','active')", UUID.randomUUID().toString(), project, editor));
        }
        Map<String, Object> settings = new HashMap<>();
        settings.put("projectId", project);
        settings.put("approvalLimit", new BigDecimal("500"));
        settings.put("expectedVersion", null);
        settings.put("approvalSteps", List.of(Map.of("fromValue", new BigDecimal("100"), "approverId", member)));
        JsonNode configured = ok(call(owner, put("/purchase-settings"), settings));
        assertEquals(List.of(member), configured.path("approvalSteps").findValuesAsText("approverId"));
        String item = ok(call(owner, post("/items"), Map.of("projectId", project, "itemCode", "OWN-STAGE-" + tag,
            "itemName", "Owner stage approval material", "itemType", "material", "unitId", "unit_kg"))).path("itemId").asText();
        JsonNode draft = ok(call(owner, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10, "unitPrice", new BigDecimal("60"))))));
        assertEquals("draft", draft.path("status").asText());
        assertTrue(draft.path("needsApproval").asBoolean());
        assertEquals(List.of(member, owner), draft.path("approvals").findValuesAsText("approverId"));
        assertFalse(draft.path("approvals").get(0).path("projectOwner").asBoolean());
        assertTrue(draft.path("approvals").get(1).path("projectOwner").asBoolean());
        assertTrue(draft.path("approvedAt").isNull());
        Fixture fixture = new Fixture(project, draft.path("purchaseOrderId").asText(), owner, member, successor);
        assertTrue(approvalRows(fixture).isEmpty());
        return fixture;
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER));
        return id;
    }

    private Map<String, Object> orderRow(Fixture fixture) {
        return jdbc.queryForMap("select * from purchase_order where purchase_order_id=?", fixture.order());
    }

    private List<Map<String, Object>> approvalRows(Fixture fixture) {
        return jdbc.queryForList("select * from purchase_order_approval where purchase_order_id=? order by approver_id", fixture.order());
    }

    private int notificationCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from notification where project_id=? and ref_id=?", Integer.class,
            fixture.project(), fixture.order());
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

    private record Fixture(String project, String order, String owner, String member, String successor) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
