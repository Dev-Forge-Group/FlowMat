package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
class PurchaseCreditChangeConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void twoChangesWaitingForTheOrderBothCommitAndTheLastChangeStands() throws Exception {
        Fixture fixture = fixture();
        List<Reply> replies = competingChanges(fixture,
            change("8.1111", "FIRST-NOTE", "First correction"),
            change("9.2222", "SECOND-NOTE", "Second correction"));

        JsonNode first = returned(ok(replies.get(0)), fixture);
        JsonNode second = returned(ok(replies.get(1)), fixture);
        assertEquals(1, first.path("creditChanges").size());
        assertEquals(2, second.path("creditChanges").size());
        assertChange(first.path("creditChanges").get(0), "8.1111", "FIRST-NOTE", "First correction", fixture);
        assertChange(second.path("creditChanges").get(0), "8.1111", "FIRST-NOTE", "First correction", fixture);
        assertChange(second.path("creditChanges").get(1), "9.2222", "SECOND-NOTE", "Second correction", fixture);

        JsonNode saved = returned(ok(call(fixture.user(), get(fixture.path()), null)), fixture);
        assertEquals(2, saved.path("creditChanges").size());
        assertChange(saved.path("creditChanges").get(0), "8.1111", "FIRST-NOTE", "First correction", fixture);
        assertChange(saved.path("creditChanges").get(1), "9.2222", "SECOND-NOTE", "Second correction", fixture);
        for (int index = 0; index < 2; index++) {
            assertEquals(OffsetDateTime.parse(second.path("creditChanges").get(index).path("changedAt").asText()).toInstant(),
                OffsetDateTime.parse(saved.path("creditChanges").get(index).path("changedAt").asText()).toInstant());
        }
        assertCurrentMatchesLast(saved);
        assertEquals("9.2222", saved.path("creditAmountText").asText());
        assertEquals(2, jdbc.queryForObject("select count(*) from purchase_credit_change where return_id=?",
            Integer.class, fixture.returnId()));
        assertOriginalCreditUnchanged(fixture);
    }

    @Test
    void equalChangeTimesUseChangeIdForHistoryAndCurrentCreditWithoutLosingAmountDigits() throws Exception {
        Fixture fixture = fixture();
        String firstAmount = "12345678901234567890.1234";
        String secondAmount = "98765432109876543210.9876";
        ok(call(fixture.user(), put(fixture.creditPath()), change(firstAmount, "FIRST-NOTE", "First correction")));
        ok(call(fixture.user(), put(fixture.creditPath()), change(secondAmount, "SECOND-NOTE", "Second correction")));

        // This isolated fixture makes timestamp ordering inconclusive and reverses insertion order with explicit ids.
        Instant sameTime = Instant.parse("2040-01-02T03:04:05.123456Z");
        String tag = UUID.randomUUID().toString();
        String lowerId = "credit-a-" + tag;
        String higherId = "credit-z-" + tag;
        assertEquals(1, jdbc.update("update purchase_credit_change set change_id=?,changed_at=? where return_id=? and reason=?",
            higherId, Timestamp.from(sameTime), fixture.returnId(), "First correction"));
        assertEquals(1, jdbc.update("update purchase_credit_change set change_id=?,changed_at=? where return_id=? and reason=?",
            lowerId, Timestamp.from(sameTime), fixture.returnId(), "Second correction"));

        JsonNode saved = returned(ok(call(fixture.user(), get(fixture.path()), null)), fixture);
        JsonNode history = saved.path("creditChanges");
        assertEquals(2, history.size());
        assertEquals(List.of(lowerId, higherId), jdbc.queryForList(
            "select change_id from purchase_credit_change where return_id=? order by changed_at,change_id",
            String.class, fixture.returnId()));
        assertChange(history.get(0), secondAmount, "SECOND-NOTE", "Second correction", fixture);
        assertChange(history.get(1), firstAmount, "FIRST-NOTE", "First correction", fixture);
        for (JsonNode change : history) {
            assertEquals(sameTime, OffsetDateTime.parse(change.path("changedAt").asText()).toInstant());
        }
        assertCurrentMatchesLast(saved);
        assertEquals(firstAmount, saved.path("creditAmountText").asText());
        assertOriginalCreditUnchanged(fixture);
    }

    private List<Reply> competingChanges(Fixture fixture, Map<String, Object> first, Map<String, Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Reply>> pending = new ArrayList<>();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            gate.executeWithoutResult(transaction -> {
                jdbc.queryForObject("select purchase_order_id from purchase_order where purchase_order_id=? for update",
                    String.class, fixture.order());
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                pending.add(pool.submit(() -> call(fixture.user(), put(fixture.creditPath()), first)));
                awaitWaiting(pending, blocker, 1);
                assertFalse(pending.get(0).isDone(), "The first change must wait on the held order row.");
                pending.add(pool.submit(() -> call(fixture.user(), put(fixture.creditPath()), second)));
                awaitWaiting(pending, blocker, 2);
                assertEquals(0, jdbc.queryForObject("select count(*) from purchase_credit_change where return_id=?",
                    Integer.class, fixture.returnId()), "Neither change may be saved before the order lock is acquired.");
            });
            // Returning or throwing from the callback releases the gate before the workers are joined.
            return List.of(pending.get(0).get(20, TimeUnit.SECONDS), pending.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The competing credit changes did not terminate.");
        }
    }

    private void awaitWaiting(List<Future<Reply>> pending, int blocker, int expectedWaiters) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (Future<Reply> request : pending) {
                assertFalse(request.isDone(), "Each submitted change must remain blocked before gate release.");
            }
            // This observation runs in the gate transaction; discard its cached statistics before reading new backend pids.
            jdbc.execute("select pg_stat_clear_snapshot()");
            Integer waiters = jdbc.queryForObject("with recursive waiters(pid) as ("
                + "select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid)) "
                + "union select a.pid from pg_stat_activity a join waiters w on w.pid = any(pg_blocking_pids(a.pid))) "
                + "select count(distinct pid) from waiters", Integer.class, blocker);
            if (Integer.valueOf(expectedWaiters).equals(waiters)) return;
            try {
                // This is an observation interval; the recursive query proves direct and indirect database lock waits.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the credit change lock queue.", exception);
            }
        }
        fail("Expected " + expectedWaiters + " credit change(s) to reach the held order row lock.");
    }

    private Fixture fixture() throws Exception {
        String user = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            user, user + "@test.local", DEMO_OWNER));
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(user, post("/projects"), Map.of("projectName", "Credit change race " + tag, "ownerId", user)))
            .path("projectId").asText();
        String item = ok(call(user, post("/items"), Map.of("projectId", project, "itemCode", "CREDIT-RACE-" + tag,
            "itemName", "Credit race material", "itemType", "material", "unitId", "unit_kg"))).path("itemId").asText();
        String order = ok(call(user, post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10, "unitPrice", new BigDecimal("2.5"))))))
            .path("purchaseOrderId").asText();
        String path = "/purchase-orders/" + order;
        String line = ok(call(user, post(path + "/order"), null)).path("lines").get(0).path("lineId").asText();
        String stock = ok(call(user, post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-CREDIT-" + tag)))))
            .path("receipts").get(0).path("inventoryId").asText();
        String returned = ok(call(user, post(path + "/returns"), Map.of("requestId", UUID.randomUUID().toString(),
            "lineId", line, "inventoryId", stock, "quantity", 4, "reason", "Mismatched delivery")))
            .path("returns").get(0).path("returnId").asText();
        Fixture fixture = new Fixture(user, order, returned);
        JsonNode credit = returned(ok(call(user, post(fixture.creditPath()),
            Map.of("amount", new BigDecimal("10"), "creditNote", "ORIGINAL"))), fixture);
        assertEquals("10.0000", credit.path("creditAmountText").asText());
        assertEquals(0, credit.path("creditChanges").size());
        return fixture;
    }

    private static Map<String, Object> change(String amount, String note, String reason) {
        return Map.of("amount", new BigDecimal(amount), "creditNote", note, "reason", reason);
    }

    private Reply call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readValue(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private static JsonNode returned(JsonNode order, Fixture fixture) {
        for (JsonNode returned : order.path("returns")) {
            if (fixture.returnId().equals(returned.path("returnId").asText())) return returned;
        }
        return fail("The created purchase return was absent from its order.");
    }

    private static void assertChange(JsonNode change, String amount, String note, String reason, Fixture fixture) {
        assertEquals(amount, change.path("amountText").asText());
        assertEquals(0, new BigDecimal(amount).compareTo(change.path("amount").decimalValue()));
        assertEquals(note, change.path("creditNote").asText());
        assertEquals(reason, change.path("reason").asText());
        assertEquals(fixture.user(), change.path("changedBy").asText());
    }

    private static void assertCurrentMatchesLast(JsonNode returned) {
        JsonNode changes = returned.path("creditChanges");
        JsonNode last = changes.get(changes.size() - 1);
        assertEquals(last.path("amountText"), returned.path("creditAmountText"));
        assertEquals(last.path("amount"), returned.path("creditAmount"));
        assertEquals(last.path("creditNote"), returned.path("creditNote"));
        assertEquals(last.path("changedBy"), returned.path("creditedBy"));
        assertEquals(OffsetDateTime.parse(last.path("changedAt").asText()).toInstant(),
            OffsetDateTime.parse(returned.path("creditedAt").asText()).toInstant());
    }

    private void assertOriginalCreditUnchanged(Fixture fixture) {
        Map<String, Object> original = jdbc.queryForMap("select amount,credit_note from purchase_credit where return_id=?",
            fixture.returnId());
        assertEquals(new BigDecimal("10.0000"), original.get("amount"));
        assertEquals("ORIGINAL", original.get("credit_note"));
    }

    private record Fixture(String user, String order, String returnId) {
        String path() { return "/purchase-orders/" + order; }
        String creditPath() { return path() + "/returns/" + returnId + "/credit"; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
