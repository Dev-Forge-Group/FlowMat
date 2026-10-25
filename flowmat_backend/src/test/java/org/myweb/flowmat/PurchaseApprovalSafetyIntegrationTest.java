package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.springframework.transaction.support.TransactionTemplate;

/** PO34 approvals and edits take turns on the order, including requests that waited for its row lock. */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseApprovalSafetyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void anEditWaitingForApprovalClearsThatApprovalBeforeTheDraftCanBeOrdered() throws Exception {
        Fixture fixture = fixture();

        Race race = race(fixture.owner(), post(fixture.path() + "/approve"), null,
            fixture.editor(), put(fixture.path()), draft(fixture.project(), fixture.item(), "11"));

        assertFalse(race.first().data().path("approvedAt").isNull());
        JsonNode edited = data(race.waiting());
        assertTrue(edited.path("approvedBy").isNull());
        assertTrue(edited.path("approvedAt").isNull());
        assertTrue(edited.path("needsApproval").asBoolean());
        assertQuantity("11", edited.path("lines").get(0).path("quantity"));
        assertStatus(409, call(fixture.editor(), post(fixture.path() + "/order"), null));
        JsonNode saved = data(call(fixture.editor(), get(fixture.path()), null));
        assertEquals("draft", saved.path("status").asText());
        assertTrue(saved.path("approvedAt").isNull());
        assertEquals(1, approvalNotificationCount(fixture));
    }

    @Test
    void approvalWaitingForAnEditApprovesTheCommittedReplacementLines() throws Exception {
        Fixture fixture = fixture();

        Race race = race(fixture.editor(), put(fixture.path()), draft(fixture.project(), fixture.item(), "11"),
            fixture.owner(), post(fixture.path() + "/approve"), null);

        JsonNode edited = race.first().data();
        JsonNode approved = data(race.waiting());
        assertTrue(edited.path("approvedAt").isNull());
        assertEquals(fixture.owner(), approved.path("approvedBy").asText());
        assertFalse(approved.path("approvedAt").isNull());
        assertEquals(edited.path("lines").get(0).path("lineId").asText(),
            approved.path("lines").get(0).path("lineId").asText());
        assertQuantity("11", approved.path("lines").get(0).path("quantity"));
        JsonNode ordered = data(call(fixture.editor(), post(fixture.path() + "/order"), null));
        assertEquals("ordered", ordered.path("status").asText());
        assertSameApproval(approved, ordered);
        assertEquals(1, approvalNotificationCount(fixture));
    }

    @Test
    void anOrderWaitingForApprovalUsesThatApprovalWithoutChangingItsAuthorOrTime() throws Exception {
        Fixture fixture = fixture();

        Race race = race(fixture.owner(), post(fixture.path() + "/approve"), null,
            fixture.editor(), post(fixture.path() + "/order"), null);

        JsonNode approved = race.first().data();
        JsonNode ordered = data(race.waiting());
        assertEquals("ordered", ordered.path("status").asText());
        assertSameApproval(approved, ordered);
        JsonNode saved = data(call(fixture.editor(), get(fixture.path()), null));
        assertSameApproval(approved, saved);
        assertStatus(409, call(fixture.owner(), post(fixture.path() + "/approve"), null));
        assertEquals(1, approvalNotificationCount(fixture));
    }

    @Test
    void simultaneousApprovalsAndARetryKeepTheFirstApprovalAndNotifyTheAuthorOnlyOnce() throws Exception {
        Fixture fixture = fixture();

        Race race = race(fixture.owner(), post(fixture.path() + "/approve"), null,
            fixture.owner(), post(fixture.path() + "/approve"), null);

        JsonNode first = race.first().data();
        assertEquals(fixture.owner(), first.path("approvedBy").asText());
        assertFalse(first.path("approvedAt").isNull());
        assertSameApproval(first, data(race.waiting()));
        assertSameApproval(first, data(call(fixture.owner(), post(fixture.path() + "/approve"), null)));
        assertSameApproval(first, data(call(fixture.editor(), get(fixture.path()), null)));
        Timestamp stored = jdbc.queryForObject("select approved_at from purchase_order where purchase_order_id=?",
            Timestamp.class, fixture.order());
        assertNotNull(stored);
        assertEquals(OffsetDateTime.parse(first.path("approvedAt").asText()).toInstant(), stored.toInstant());
        assertEquals(1, approvalNotificationCount(fixture));
        long visible = 0;
        for (JsonNode notification : data(call(fixture.editor(), get("/notifications"), null))) {
            if (fixture.order().equals(notification.path("refId").asText())
                && "purchase_approved".equals(notification.path("type").asText())) {
                visible++;
            }
        }
        assertEquals(1L, visible);
    }

    @Test
    void anApprovalWaitingForTheOrderRechecksItsFormerOwnersRemovedMembership() throws Exception {
        Fixture fixture = fixture();
        String newOwner = user();
        member(fixture.project(), newOwner, "editor");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> waiting = new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbc.queryForObject("select purchase_order_id from purchase_order where purchase_order_id=? for update",
                    String.class, fixture.order());
                Future<Reply> request = pool.submit(() -> call(fixture.owner(), post(fixture.path() + "/approve"), null));
                awaitOrderLock(request);
                // There is no owner-transfer API. Change both parts of this isolated fixture atomically, so the
                // former owner does not retain an active owner-role membership after project.owner_id changes.
                assertEquals(1, jdbc.update("update project set owner_id=? where project_id=?", newOwner, fixture.project()));
                assertEquals(1, jdbc.update("update project_member set project_role='viewer', member_status='removed'"
                    + " where project_id=? and user_id=?", fixture.project(), fixture.owner()));
                assertEquals(1, jdbc.update("update project_member set project_role='owner' where project_id=? and user_id=?",
                    fixture.project(), newOwner));
                return request;
            });
            assertNotNull(waiting);
            assertStatus(403, waiting.get(15, TimeUnit.SECONDS));
            JsonNode rejected = data(call(fixture.editor(), get(fixture.path()), null));
            assertTrue(rejected.path("approvedBy").isNull());
            assertTrue(rejected.path("approvedAt").isNull());
            assertEquals(0, approvalNotificationCount(fixture));
            JsonNode approved = data(call(newOwner, post(fixture.path() + "/approve"), null));
            assertEquals(newOwner, approved.path("approvedBy").asText());
            assertFalse(approved.path("approvedAt").isNull());
            assertEquals(1, approvalNotificationCount(fixture));
        } finally {
            finish(pool);
        }
    }

    /** The first real API request holds its transaction open until the second is observed at its database lock. */
    private Race race(String firstUser, MockHttpServletRequestBuilder firstRequest, Object firstBody,
                      String waitingUser, MockHttpServletRequestBuilder waitingRequest, Object waitingBody) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Pending pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                try {
                    Reply first = call(firstUser, firstRequest, firstBody);
                    assertStatus(200, first);
                    Future<Reply> waiting = pool.submit(() -> call(waitingUser, waitingRequest, waitingBody));
                    awaitOrderLock(waiting);
                    return new Pending(first, waiting);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertNotNull(pending);
            return new Race(pending.first(), pending.waiting().get(15, TimeUnit.SECONDS));
        } finally {
            finish(pool);
        }
    }

    private void awaitOrderLock(Future<?> request) {
        Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        assertNotNull(blocker);
        DatabaseContention.awaitWaitingOrDone(jdbc, request, blocker);
        assertFalse(request.isDone(), "The competing request must wait for the purchase order's held row lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = user();
        String editor = user();
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = data(call(owner, post("/projects"), Map.of("projectName", "Approval safety " + tag, "ownerId", owner)))
            .path("projectId").asText();
        member(project, editor, "editor");
        String item = data(call(owner, post("/items"), Map.of("projectId", project, "itemCode", "APPROVE-" + tag,
            "itemName", "Approval material", "itemType", "material", "unitId", "unit_kg"))).path("itemId").asText();
        Map<String, Object> setting = new HashMap<>();
        setting.put("projectId", project);
        setting.put("approvalLimit", new BigDecimal("100"));
        setting.put("expectedVersion", null);
        data(call(owner, put("/purchase-settings"), setting));
        JsonNode draft = data(call(editor, post("/purchase-orders"), draft(project, item, "10")));
        assertTrue(draft.path("needsApproval").asBoolean());
        assertTrue(draft.path("approvedAt").isNull());
        return new Fixture(project, item, draft.path("purchaseOrderId").asText(), owner, editor);
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER));
        return id;
    }

    private void member(String project, String user, String role) {
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role,member_status) values(?,?,?,?,'active')",
            UUID.randomUUID().toString(), project, user, role);
    }

    private int approvalNotificationCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from notification where project_id=? and ref_id=? and nfc_type='purchase_approved'",
            Integer.class, fixture.project(), fixture.order());
    }

    private static Map<String, Object> draft(String project, String item, String quantity) {
        return Map.of("projectId", project, "supplierName", "Mill Co", "lines", List.of(Map.of("itemId", item,
            "quantity", new BigDecimal(quantity), "unitPrice", new BigDecimal("20"))));
    }

    private Reply call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode data(Reply reply) {
        assertStatus(200, reply);
        return reply.data();
    }

    private static void assertStatus(int expected, Reply reply) {
        assertEquals(expected, reply.status(), reply.message());
    }

    private static void assertSameApproval(JsonNode expected, JsonNode actual) {
        assertEquals(expected.path("approvedBy").asText(), actual.path("approvedBy").asText());
        assertEquals(OffsetDateTime.parse(expected.path("approvedAt").asText()).toInstant(),
            OffsetDateTime.parse(actual.path("approvedAt").asText()).toInstant());
    }

    private static void assertQuantity(String expected, JsonNode actual) {
        assertTrue(actual.isNumber());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private static void finish(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The test's competing request did not terminate.");
    }

    private record Fixture(String project, String item, String order, String owner, String editor) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }

    private record Pending(Reply first, Future<Reply> waiting) {
    }

    private record Race(Reply first, Reply waiting) {
    }
}
