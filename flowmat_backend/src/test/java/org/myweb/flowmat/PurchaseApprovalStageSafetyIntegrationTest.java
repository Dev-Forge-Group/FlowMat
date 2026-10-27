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

@AutoConfigureMockMvc
@Timeout(60)
class PurchaseApprovalStageSafetyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aWaitingStageApproverCannotApproveAfterTheOwnerChangesTheirRoleToViewer() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> before = approvalState(fixture);
        assertEquals(0, approvalCount(fixture));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Future<Reply> pending = gate.execute(transaction -> {
                jdbc.queryForObject("select purchase_order_id from purchase_order where purchase_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(fixture.approver(), post(fixture.path() + "/approve"), null));
                awaitOrderLock(waiting, blocker);
                try {
                    JsonNode member = ok(call(fixture.owner(), put("/project-members/" + fixture.membership() + "/role"),
                        Map.of("projectRole", "viewer")));
                    assertEquals(fixture.approver(), member.path("userId").asText());
                    assertEquals("viewer", member.path("projectRole").asText());
                } catch (Exception exception) {
                    throw new IllegalStateException("Changing the isolated approver's role failed.", exception);
                }
                return waiting;
            });
            assertNotNull(pending);
            Reply rejected = pending.get(20, TimeUnit.SECONDS);
            assertEquals(403, rejected.status(), rejected.message());
            assertEquals(0, approvalCount(fixture), "A former editor must not leave a stage approval after waiting.");
            assertEquals(before, approvalState(fixture), "Refusing the former editor must preserve the order's approval state.");

            JsonNode order = ok(call(fixture.owner(), get(fixture.path()), null));
            assertEquals("draft", order.path("status").asText());
            assertTrue(order.path("approvedBy").isNull());
            assertTrue(order.path("approvedAt").isNull());
            assertTrue(order.path("needsApproval").asBoolean());
            assertEquals(1, order.path("approvals").size());
            assertEquals(fixture.approver(), order.path("approvals").get(0).path("approverId").asText());
            assertTrue(order.path("approvals").get(0).path("approvedAt").isNull());

            JsonNode settings = ok(call(fixture.owner(), get("/purchase-settings").param("projectId", fixture.project()), null));
            assertEquals(1, settings.path("approvalSteps").size());
            JsonNode step = settings.path("approvalSteps").get(0);
            assertEquals(fixture.approver(), step.path("approverId").asText());
            assertTrue(step.path("canApprove").isBoolean());
            assertFalse(step.path("canApprove").asBoolean());
        } finally {
            // The gate callback commits or rolls back before this joins its waiting request.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The waiting stage approval did not terminate.");
        }
    }

    private void awaitOrderLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The stage approval must wait on the purchase order's held row lock.");
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))", Boolean.class, blocker))) {
                return;
            }
            try {
                // Polling only: pg_blocking_pids supplies the evidence of the database wait.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the stage approval lock.", exception);
            }
        }
        fail("The stage approval did not reach the held purchase order row lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String approver = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "Stage approval safety " + tag, "ownerId", owner)))
            .path("projectId").asText();
        String membership = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status)"
            + " values(?,?,?,'editor','active')", membership, project, approver));
        Map<String, Object> setting = new HashMap<>();
        setting.put("projectId", project);
        setting.put("approvalLimit", new BigDecimal("1000"));
        setting.put("expectedVersion", null);
        setting.put("approvalSteps", List.of(Map.of("fromValue", new BigDecimal("100"), "approverId", approver)));
        JsonNode settings = ok(call(owner, put("/purchase-settings"), setting));
        assertEquals(1, settings.path("approvalSteps").size());
        assertTrue(settings.path("approvalSteps").get(0).path("canApprove").asBoolean());
        String item = ok(call(owner, post("/items"), Map.of("projectId", project, "itemCode", "STAGE-" + tag,
            "itemName", "Stage approval material", "itemType", "material", "unitId", "unit_kg"))).path("itemId").asText();
        JsonNode draft = ok(call(owner, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10, "unitPrice", new BigDecimal("20"))))));
        assertEquals("draft", draft.path("status").asText());
        assertTrue(draft.path("approvedBy").isNull());
        assertTrue(draft.path("approvedAt").isNull());
        assertTrue(draft.path("needsApproval").asBoolean());
        assertEquals(1, draft.path("approvals").size());
        assertEquals(approver, draft.path("approvals").get(0).path("approverId").asText());
        assertTrue(draft.path("approvals").get(0).path("approvedAt").isNull());
        return new Fixture(owner, approver, membership, project, draft.path("purchaseOrderId").asText());
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER));
        return id;
    }

    private int approvalCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from purchase_order_approval where purchase_order_id=?", Integer.class, fixture.order());
    }

    private Map<String, Object> approvalState(Fixture fixture) {
        return jdbc.queryForMap("select status,approved_by,approved_at from purchase_order where purchase_order_id=?", fixture.order());
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

    private record Fixture(String owner, String approver, String membership, String project, String order) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
