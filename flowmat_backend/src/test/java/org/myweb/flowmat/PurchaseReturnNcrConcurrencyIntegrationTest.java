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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** PO42: a shared return key replays only the same NCR, including requests waiting for the order's row lock. */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseReturnNcrConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest(name = "same NCR = {0}: concurrent returns debit stock once")
    @ValueSource(booleans = {true, false})
    void aSharedRequestKeyReplaysTheSameNcrAndRefusesADifferentValidNcr(boolean identical) throws Exception {
        Fixture fixture = fixture();
        List<JsonNode> ncrsBefore = ncrs(fixture);
        UUID requestId = UUID.randomUUID();
        List<Map<String, Object>> bodies = List.of(back(fixture, requestId, fixture.first()),
            back(fixture, requestId, identical ? fixture.first() : fixture.second()));

        List<Reply> replies = competingReturns(fixture, bodies);

        assertEquals(identical ? List.of(200, 200) : List.of(200, 409),
            replies.stream().map(Reply::status).sorted().toList(), replies.toString());
        // Identify the winner from responses rather than assuming the order in which submitted workers acquired the lock.
        int winnerIndex = replies.get(0).status() == 200 ? 0 : 1;
        Ncr winner = identical || winnerIndex == 0 ? fixture.first() : fixture.second();
        JsonNode winningReturn = replies.get(winnerIndex).data().path("returns").get(0);
        if (identical) {
            assertEquals(replies.get(0).data().path("returns").get(0).path("returnId"),
                replies.get(1).data().path("returns").get(0).path("returnId"));
        } else {
            assertTrue(replies.get(1 - winnerIndex).message().contains("different return"));
        }

        State committed = state(fixture);
        assertOneReturn(fixture, requestId, winner, winningReturn, committed);
        assertEquals(ncrsBefore, committed.ncrs(), "Neither successful return nor conflicting request may rewrite either NCR.");

        JsonNode replayed = ok(call(post(fixture.returnPath()), bodies.get(winnerIndex)));
        assertEquals(winningReturn.path("returnId"), replayed.path("returns").get(0).path("returnId"));
        assertEquals(winner.id(), replayed.path("returns").get(0).path("nonconformityId").asText());
        assertEquals(committed, state(fixture), "A normal retry of the winning body must not debit or change any record again.");
    }

    private List<Reply> competingReturns(Fixture fixture, List<Map<String, Object>> bodies) throws Exception {
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
                pending.add(pool.submit(() -> call(post(fixture.returnPath()), bodies.get(0))));
                awaitWaiting(pending, blocker, 1);
                pending.add(pool.submit(() -> call(post(fixture.returnPath()), bodies.get(1))));
                awaitWaiting(pending, blocker, 2);
                assertEquals(0, returnCount(fixture), "Neither waiting request may save a return before acquiring the order lock.");
            });
            // Returning or throwing from the callback releases the gate before workers are joined.
            return List.of(pending.get(0).get(20, TimeUnit.SECONDS), pending.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The competing NCR returns did not terminate.");
        }
    }

    private void awaitWaiting(List<Future<Reply>> pending, int blocker, int expectedWaiters) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (Future<Reply> request : pending) {
                assertFalse(request.isDone(), "Each return must remain blocked before gate release.");
            }
            jdbc.execute("select pg_stat_clear_snapshot()");
            Integer waiters = jdbc.queryForObject("with recursive waiters(pid) as ("
                + "select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid)) "
                + "union select a.pid from pg_stat_activity a join waiters w on w.pid = any(pg_blocking_pids(a.pid))) "
                + "select count(distinct pid) from waiters", Integer.class, blocker);
            if (Integer.valueOf(expectedWaiters).equals(waiters)) return;
            try {
                // Polling only: the recursive blocking chain proves direct and indirect database lock waits.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the purchase return lock queue.", exception);
            }
        }
        fail("Expected " + expectedWaiters + " purchase return(s) to reach the held order row lock.");
    }

    private void assertOneReturn(Fixture fixture, UUID requestId, Ncr winner, JsonNode winningReturn, State committed) {
        assertEquals(1, returnCount(fixture));
        JsonNode returns = committed.order().path("returns");
        assertEquals(1, returns.size());
        JsonNode saved = returns.get(0);
        assertEquals(winningReturn.path("returnId"), saved.path("returnId"));
        assertEquals(winner.id(), saved.path("nonconformityId").asText());
        assertEquals(winner.number(), saved.path("ncrNo").asText());
        assertDecimal("2", saved.path("quantity"));
        assertDecimal("8", committed.stock().path("quantity"));
        assertEquals("received", committed.order().path("status").asText());
        assertEquals(1, committed.order().path("lines").size());
        assertDecimal("10", committed.order().path("lines").get(0).path("receivedQuantity"));
        assertDecimal("2", committed.order().path("lines").get(0).path("returnedQuantity"));

        Map<String, Object> stored = jdbc.queryForMap("select nonconformity_id,ncr_no from purchase_return"
            + " where purchase_order_id=? and request_id=?", fixture.order(), requestId);
        assertEquals(winner.id(), stored.get("nonconformity_id"));
        assertEquals(winner.number(), stored.get("ncr_no"));
        int movements = 0;
        for (JsonNode movement : committed.ledger()) {
            if ("purchase_return".equals(movement.path("referenceType").asText())) {
                movements++;
                assertEquals(saved.path("returnId").asText(), movement.path("referenceId").asText());
                assertEquals("adjustment", movement.path("transactionType").asText());
                assertDecimal("-2", movement.path("quantityDelta"));
            }
        }
        assertEquals(1, movements, "Exactly one stock decrease must belong to the return.");
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?"
            + " and reference_type='purchase_return'", Integer.class, fixture.project()));
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(post("/projects"), Map.of("projectName", "Concurrent NCR returns " + tag, "ownerId", DEMO_OWNER)))
            .path("projectId").asText();
        String item = ok(call(post("/items"), Map.of("projectId", project, "itemCode", "NCR-RACE-" + tag,
            "itemName", "NCR return material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y")))
            .path("itemId").asText();
        String order = ok(call(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill " + tag,
            "lines", List.of(Map.of("itemId", item, "quantity", 10))))).path("purchaseOrderId").asText();
        String path = "/purchase-orders/" + order;
        String line = ok(call(post(path + "/order"), null)).path("lines").get(0).path("lineId").asText();
        String stock = ok(call(post(path + "/receipts"), Map.of("requestId", UUID.randomUUID().toString(),
            "lines", List.of(Map.of("lineId", line, "quantity", 10, "location", "WH-A", "lotNo", "RACE-LOT-" + tag,
                "expiryDate", "2042-01-01"))))).path("receipts").get(0).path("inventoryId").asText();
        JsonNode inventory = ok(call(get("/inventories/" + stock), null));
        String lot = inventory.path("lotId").asText();
        assertTrue(!lot.isBlank() && !"null".equals(lot));
        assertDecimal("10", inventory.path("quantity"));
        Ncr first = ncr(project, item, lot, "First NCR " + tag);
        Ncr second = ncr(project, item, lot, "Second NCR " + tag);
        assertFalse(first.id().equals(second.id()));
        return new Fixture(project, order, line, stock, first, second);
    }

    private Ncr ncr(String project, String item, String lot, String title) throws Exception {
        String id = ok(call(post("/nonconformities"), Map.of("projectId", project, "title", title,
            "itemId", item, "lotId", lot, "severity", "major"))).path("nonconformityId").asText();
        JsonNode ready = ok(call(put("/nonconformities/" + id), Map.of("disposition", "return_to_supplier")));
        assertEquals("open", ready.path("status").asText());
        assertEquals("return_to_supplier", ready.path("disposition").asText());
        assertEquals(item, ready.path("itemId").asText());
        assertEquals(lot, ready.path("lotId").asText());
        return new Ncr(id, ready.path("ncrNo").asText());
    }

    private static Map<String, Object> back(Fixture fixture, UUID requestId, Ncr ncr) {
        return Map.of("requestId", requestId, "lineId", fixture.line(), "inventoryId", fixture.stock(), "quantity", 2,
            "reason", "Return the received NCR LOT", "nonconformityId", ncr.id(), "replacement", false);
    }

    private int returnCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from purchase_return where purchase_order_id=?", Integer.class, fixture.order());
    }

    private List<JsonNode> ncrs(Fixture fixture) throws Exception {
        return List.of(ok(call(get("/nonconformities/" + fixture.first().id()), null)),
            ok(call(get("/nonconformities/" + fixture.second().id()), null)));
    }

    private State state(Fixture fixture) throws Exception {
        return new State(ok(call(get(fixture.path()), null)), ok(call(get("/inventories/" + fixture.stock()), null)),
            ok(call(get("/inventory-transactions").param("projectId", fixture.project()).param("inventoryId", fixture.stock()), null)),
            ncrs(fixture));
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode envelope = mapper.readTree(response.getContentAsString());
        return new Reply(response.getStatus(), envelope.path("data"), envelope.path("message").asText());
    }

    private static JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.message());
        return reply.data();
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), String.valueOf(actual));
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private record Fixture(String project, String order, String line, String stock, Ncr first, Ncr second) {
        String path() { return "/purchase-orders/" + order; }
        String returnPath() { return path() + "/returns"; }
    }

    private record Ncr(String id, String number) {
    }

    private record Reply(int status, JsonNode data, String message) {
    }

    private record State(JsonNode order, JsonNode stock, JsonNode ledger, List<JsonNode> ncrs) {
    }
}
