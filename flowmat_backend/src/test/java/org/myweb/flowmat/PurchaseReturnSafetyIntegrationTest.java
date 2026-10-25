package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.application.StockReturnService;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReturnCommand.StockReturn;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** PO26-PO28: committed requests, order-lock contention and inventory refusal without partial returns. */
@AutoConfigureMockMvc
@Timeout(60)
class PurchaseReturnSafetyIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StockReturnService stockReturnService;

    @AfterEach
    void clearOnlyThisClassReturnGate() {
        reset(stockReturnService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anInFlightRequestIdIsAppliedOnceAndDifferentContentsAreRefused(boolean identical) throws Exception {
        Fixture fixture = fixture(false);
        Map<String, Object> firstBody = back(fixture, UUID.randomUUID(), "4");
        Map<String, Object> secondBody = new HashMap<>(firstBody);
        if (!identical) secondBody.put("quantity", new BigDecimal("2"));
        Gate gate = pauseFirstReturn(fixture);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> first = pool.submit(() -> call(post(fixture.path() + "/returns"), firstBody));
            assertTrue(gate.entered.await(15, TimeUnit.SECONDS), "The first return did not reach inventory.");
            Future<Reply> second = pool.submit(() -> call(post(fixture.path() + "/returns"), secondBody));
            DatabaseContention.awaitWaitingOrDone(jdbc, second, gate.pid.get());
            assertFalse(second.isDone(), "The duplicate must wait for the purchase order's uncommitted return.");
            gate.release.countDown();
            Reply initial = first.get(15, TimeUnit.SECONDS);
            Reply repeated = second.get(15, TimeUnit.SECONDS);
            assertStatus(200, initial);
            assertStatus(identical ? 200 : 409, repeated);
            if (identical) {
                assertEquals(initial.data().path("returns").get(0).path("returnId").asText(),
                    repeated.data().path("returns").get(0).path("returnId").asText());
            }
            assertOneReturn(fixture, "4", "6");
        } finally {
            finish(pool, gate);
        }
    }

    @Test
    void concurrentReturnsCannotExceedWhatWasReceivedOnOneLine() throws Exception {
        Fixture fixture = fixture(false);
        Gate gate = pauseFirstReturn(fixture);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Reply> first = pool.submit(() -> call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "7")));
            assertTrue(gate.entered.await(15, TimeUnit.SECONDS), "The first return did not reach inventory.");
            Future<Reply> second = pool.submit(() -> call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "7")));
            DatabaseContention.awaitWaitingOrDone(jdbc, second, gate.pid.get());
            assertFalse(second.isDone(), "The competing return must wait for the line's committed returned quantity.");
            gate.release.countDown();
            assertStatus(200, first.get(15, TimeUnit.SECONDS));
            assertStatus(400, second.get(15, TimeUnit.SECONDS));
            assertOneReturn(fixture, "7", "3");
        } finally {
            finish(pool, gate);
        }
    }

    @Test
    void aReservationCommittedDuringReturnValidationCannotBeTakenByTheReturn() throws Exception {
        Fixture fixture = fixture(false);
        Gate gate = pauseFirstReturn(fixture);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> returning = pool.submit(() -> call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "6")));
            assertTrue(gate.entered.await(15, TimeUnit.SECONDS), "The return did not reach inventory.");
            data(call(post("/inventory-transactions"), movement(fixture, "reserve", "6")));
            gate.release.countDown();
            assertStatus(409, returning.get(15, TimeUnit.SECONDS));
            assertNoReturn(fixture);
            JsonNode stock = data(call(get("/inventories/" + fixture.stock()), null));
            assertAmount("10", stock.path("quantity"));
            assertAmount("6", stock.path("reservedQuantity"));
            assertAmount("4", stock.path("availableQuantity"));
            assertEquals(1, jdbc.queryForObject("select count(*) from inventory_transaction where project_id=? and transaction_type='reserve'",
                Integer.class, fixture.project()));
        } finally {
            finish(pool, gate);
        }
    }

    @Test
    void aClosedLotRefusesTheReturnWithoutAddingALedgerOrReturnRow() throws Exception {
        Fixture fixture = fixture(true);
        data(call(post("/inventory-transactions"), movement(fixture, "issue", "10")));
        data(call(post("/lots/" + fixture.lot() + "/close"), null));
        int movementsBefore = movementCount(fixture);

        assertStatus(409, call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "1")));

        assertNoReturn(fixture);
        assertEquals(movementsBefore, movementCount(fixture));
        assertAmount("0", data(call(get("/inventories/" + fixture.stock()), null)).path("quantity"));
        assertEquals("closed", data(call(get("/lots/" + fixture.lot()), null)).path("lotStatus").asText());
    }

    @Test
    void aDeletedStockRecordRefusesTheReturnWithoutPartialWrites() throws Exception {
        Fixture fixture = fixture(false);
        data(call(post("/inventory-transactions"), movement(fixture, "issue", "10")));
        data(call(delete("/inventories/" + fixture.stock()), null));
        int movementsBefore = movementCount(fixture);

        assertStatus(400, call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "1")));

        assertNoReturn(fixture);
        assertEquals(movementsBefore, movementCount(fixture));
        assertStatus(404, call(get("/inventories/" + fixture.stock()), null));
        assertEquals("Y", jdbc.queryForObject("select deleted_yn from inventory where inventory_id=?", String.class, fixture.stock()));
    }

    @Test
    void stockFromAnotherProjectIsRefusedEvenWhenTheCallerOwnsBothProjects() throws Exception {
        Fixture fixture = fixture(false);
        Fixture other = fixture(false);
        Map<String, Object> request = new HashMap<>(back(fixture, UUID.randomUUID(), "1"));
        request.put("inventoryId", other.stock());
        int before = movementCount(fixture);
        int otherBefore = movementCount(other);

        Reply refusal = call(post(fixture.path() + "/returns"), request);

        assertStatus(400, refusal);
        assertEquals("The stock record to send back was not found in this project.", refusal.message());
        assertNoReturn(fixture);
        assertNoReturn(other);
        assertEquals(before, movementCount(fixture));
        assertEquals(otherBefore, movementCount(other));
        assertAmount("10", data(call(get("/inventories/" + fixture.stock()), null)).path("quantity"));
        assertAmount("10", data(call(get("/inventories/" + other.stock()), null)).path("quantity"));
    }

    @Test
    void theReturnSnapshotsTheLocationHeldWhenItsStockActuallyDecreases() throws Exception {
        Fixture fixture = fixture(false);
        Gate gate = pauseFirstReturn(fixture);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Reply> returning = pool.submit(() -> call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "4")));
            assertTrue(gate.entered.await(15, TimeUnit.SECONDS), "The return did not reach inventory.");
            JsonNode current = data(call(get("/inventories/" + fixture.stock()), null));
            data(call(put("/inventories/" + fixture.stock()), Map.of("projectId", fixture.project(), "itemId", fixture.item(),
                "quantity", 10, "reservedQuantity", 0, "location", "WH-B", "expectedVersion", current.path("version").asLong())));
            gate.release.countDown();
            Reply result = returning.get(15, TimeUnit.SECONDS);
            assertStatus(200, result);
            assertEquals("WH-B", gate.locationAtDecrease.get(), "The real inventory command must observe the committed new place.");
            assertAmount("6", data(call(get("/inventories/" + fixture.stock()), null)).path("quantity"));
            assertEquals(gate.locationAtDecrease.get(), result.data().path("returns").get(0).path("location").asText(),
                "The return's immutable place must describe the row locked by its actual decrease.");
        } finally {
            finish(pool, gate);
        }
    }

    @Test
    void aSupplierReturnIsACorrectionWithoutConsumptionWasteOrAPlaceTransfer() throws Exception {
        OffsetDateTime from = OffsetDateTime.now().minusMinutes(1);
        Fixture fixture = fixture(false);
        data(call(post(fixture.path() + "/returns"), back(fixture, UUID.randomUUID(), "4")));
        OffsetDateTime to = OffsetDateTime.now().plusSeconds(1);

        JsonNode summary = data(call(get("/stock-movement-summary").param("projectId", fixture.project())
            .param("from", from.toString()).param("to", to.toString()), null));
        assertEquals(1, summary.path("lines").size());
        JsonNode movement = summary.path("lines").get(0);
        assertEquals(fixture.item(), movement.path("itemId").asText());
        assertAmount("0", movement.path("opening"));
        assertAmount("10", movement.path("received"));
        assertAmount("0", movement.path("issued"));
        assertAmount("0", movement.path("consumed"));
        assertAmount("0", movement.path("transferred"));
        assertAmount("-4", movement.path("corrected"));
        assertAmount("6", movement.path("closing"));
        assertAmount("0", movement.path("unexplained"));

        JsonNode analysis = data(call(get("/stock-analysis").param("projectId", fixture.project()).param("days", "30"), null));
        assertEquals(1, analysis.path("lines").size());
        JsonNode consumption = analysis.path("lines").get(0);
        assertEquals(fixture.item(), consumption.path("itemId").asText());
        assertAmount("6", consumption.path("onHandQuantity"));
        assertAmount("6", consumption.path("usableQuantity"));
        assertAmount("0", consumption.path("consumedQuantity"));
        assertAmount("0", consumption.path("averageDailyConsumption"));
        assertTrue(consumption.path("daysOfCover").isNull());

        JsonNode waste = data(call(get("/stock-waste").param("projectId", fixture.project()).param("days", "30"), null));
        assertEquals(0, waste.path("lines").size());
        assertAmount("0", waste.path("value"));
        assertAmount("0", waste.path("expiredValue"));
        assertAmount("0", waste.path("defectValue"));
        assertAmount("0", waste.path("countLossValue"));
        JsonNode transfers = data(call(get("/stock-analysis/transfers").param("projectId", fixture.project()).param("days", "30"), null));
        assertEquals(0, transfers.path("routes").size());
        assertOneReturn(fixture, "4", "6");
    }

    @Test
    void aReturnBelowTheExistingMinimumOpensOneAlertAndRetryDoesNotDuplicateIt() throws Exception {
        Fixture fixture = fixture(false);
        data(call(put("/items/" + fixture.item()), Map.of("safetyStockQty", 8)));
        JsonNode current = data(call(get("/inventories/" + fixture.stock()), null));
        data(call(put("/inventories/" + fixture.stock()), Map.of("projectId", fixture.project(), "itemId", fixture.item(),
            "quantity", 10, "reservedQuantity", 0, "location", "WH-A", "minThreshold", 8,
            "expectedVersion", current.path("version").asLong())));
        assertEquals(0, data(call(get("/stock-alerts").param("projectId", fixture.project()).param("openOnly", "true"), null)).size());
        assertEquals(0, data(call(get("/stock-alerts/reorder").param("projectId", fixture.project()), null)).size());
        Map<String, Object> request = back(fixture, UUID.randomUUID(), "4");

        data(call(post(fixture.path() + "/returns"), request));

        JsonNode alerts = data(call(get("/stock-alerts").param("projectId", fixture.project()).param("openOnly", "true"), null));
        assertEquals(1, alerts.size());
        JsonNode alert = alerts.get(0);
        assertEquals(fixture.stock(), alert.path("inventoryId").asText());
        assertEquals(fixture.item(), alert.path("itemId").asText());
        assertEquals("low", alert.path("alertType").asText());
        assertEquals("warning", alert.path("severity").asText());
        assertAmount("8", alert.path("thresholdValue"));
        assertAmount("6", alert.path("actualValue"));
        assertFalse(alert.path("resolved").asBoolean());
        String alertId = alert.path("stockAlertId").asText();
        JsonNode reorder = data(call(get("/stock-alerts/reorder").param("projectId", fixture.project()), null));
        assertEquals(1, reorder.size());
        assertEquals(fixture.item(), reorder.get(0).path("itemId").asText());
        assertAmount("8", reorder.get(0).path("safetyStockQty"));
        assertAmount("6", reorder.get(0).path("availableQuantity"));
        assertAmount("2", reorder.get(0).path("shortageQuantity"));
        int movements = movementCount(fixture);

        data(call(post(fixture.path() + "/returns"), request));

        JsonNode repeatedAlerts = data(call(get("/stock-alerts").param("projectId", fixture.project()).param("openOnly", "true"), null));
        assertEquals(1, repeatedAlerts.size());
        assertEquals(alertId, repeatedAlerts.get(0).path("stockAlertId").asText());
        assertAmount("6", repeatedAlerts.get(0).path("actualValue"));
        assertEquals(1, jdbc.queryForObject("select count(*) from stock_alert where project_id=? and inventory_id=? and alert_type='low'",
            Integer.class, fixture.project(), fixture.stock()));
        assertEquals(movements, movementCount(fixture));
        assertOneReturn(fixture, "4", "6");
    }

    private Gate pauseFirstReturn(Fixture fixture) {
        Gate gate = new Gate();
        doAnswer(invocation -> {
            StockReturn request = invocation.getArgument(0);
            boolean pause = fixture.stock().equals(request.inventoryId()) && gate.first.compareAndSet(true, false);
            if (pause) {
                gate.pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                gate.entered.countDown();
                assertTrue(gate.release.await(20, TimeUnit.SECONDS), "The inventory command was not released.");
            }
            Object result = invocation.callRealMethod();
            if (pause) gate.locationAtDecrease.set(jdbc.queryForObject("select location from inventory where inventory_id=?",
                String.class, fixture.stock()));
            return result;
        }).when(stockReturnService).giveBack(any(StockReturn.class));
        return gate;
    }

    private Fixture fixture(boolean lotTracked) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = data(call(post("/projects"), Map.of("projectName", "Return safety " + tag, "ownerId", DEMO_OWNER)))
            .path("projectId").asText();
        for (String place : List.of("WH-A", "WH-B")) {
            data(call(post("/storage-locations"), Map.of("projectId", project, "locationCode", place, "locationType", "bin")));
        }
        String item = data(call(post("/items"), Map.of("projectId", project, "itemCode", "RETURN-" + tag, "itemName", "Return material",
            "itemType", "material", "unitId", "unit_kg", "lotManageYn", lotTracked ? "Y" : "N"))).path("itemId").asText();
        JsonNode draft = data(call(post("/purchase-orders"), Map.of("projectId", project, "supplierName", "Mill Co",
            "lines", List.of(Map.of("itemId", item, "quantity", 10)))));
        String order = draft.path("purchaseOrderId").asText();
        String line = draft.path("lines").get(0).path("lineId").asText();
        data(call(post("/purchase-orders/" + order + "/order"), null));
        Map<String, Object> receipt = new HashMap<>(Map.of("lineId", line, "quantity", 10, "location", "WH-A"));
        if (lotTracked) {
            receipt.put("lotNo", "LOT-" + tag);
            receipt.put("expiryDate", "2042-01-01");
        }
        JsonNode received = data(call(post("/purchase-orders/" + order + "/receipts"),
            Map.of("requestId", UUID.randomUUID(), "lines", List.of(receipt))));
        String stock = received.path("receipts").get(0).path("inventoryId").asText();
        String lot = data(call(get("/inventories/" + stock), null)).path("lotId").asText(null);
        return new Fixture(project, item, order, line, stock, lot);
    }

    private void assertOneReturn(Fixture fixture, String quantity, String remainingStock) throws Exception {
        JsonNode order = data(call(get(fixture.path()), null));
        assertEquals(1, order.path("returns").size());
        assertEquals("received", order.path("status").asText());
        assertAmount("10", order.path("lines").get(0).path("receivedQuantity"));
        assertAmount(quantity, order.path("lines").get(0).path("returnedQuantity"));
        assertAmount(remainingStock, data(call(get("/inventories/" + fixture.stock()), null)).path("quantity"));
        assertEquals(1, jdbc.queryForObject("select count(*) from purchase_return where purchase_order_id=?", Integer.class, fixture.order()));
        assertEquals(1, returnMovementCount(fixture));
        BigDecimal delta = jdbc.queryForObject("select quantity_delta from inventory_transaction where project_id=? and reference_type='purchase_return'",
            BigDecimal.class, fixture.project());
        assertEquals(0, new BigDecimal(quantity).negate().compareTo(delta));
    }

    private void assertNoReturn(Fixture fixture) throws Exception {
        assertEquals(0, data(call(get(fixture.path()), null)).path("returns").size());
        assertEquals(0, jdbc.queryForObject("select count(*) from purchase_return where purchase_order_id=?", Integer.class, fixture.order()));
        assertEquals(0, returnMovementCount(fixture));
    }

    private int returnMovementCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from inventory_transaction where project_id=? and reference_type='purchase_return'",
            Integer.class, fixture.project());
    }

    private int movementCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?", Integer.class, fixture.project());
    }

    private static Map<String, Object> back(Fixture fixture, UUID requestId, String quantity) {
        return Map.of("requestId", requestId, "lineId", fixture.line(), "inventoryId", fixture.stock(),
            "quantity", new BigDecimal(quantity), "reason", "Wet bags");
    }

    private static Map<String, Object> movement(Fixture fixture, String type, String quantity) {
        return Map.of("inventoryId", fixture.stock(), "transactionType", type, "quantity", new BigDecimal(quantity),
            "requestId", UUID.randomUUID().toString());
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
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

    private static void assertAmount(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), "The quantity must be a JSON number.");
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
    }

    private static void finish(ExecutorService pool, Gate gate) throws InterruptedException {
        gate.release.countDown();
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The test's competing requests did not terminate.");
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        final AtomicInteger pid = new AtomicInteger();
        final AtomicReference<String> locationAtDecrease = new AtomicReference<>();
    }

    private record Fixture(String project, String item, String order, String line, String stock, String lot) {
        String path() { return "/purchase-orders/" + order; }
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
