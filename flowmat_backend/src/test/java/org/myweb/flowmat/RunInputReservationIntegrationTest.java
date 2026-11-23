package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class RunInputReservationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void anActualRunTakesItsOwnReservationsInExpiryOrderAndPreservesAnotherOrder() throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), "actual");

        JsonNode recorded = data(fefo(fixture, runId, 8));

        assertEquals(2, recorded.size());
        assertEquals(fixture.soonStock(), recorded.get(0).path("inventoryId").asText());
        assertEquals(5.0, recorded.get(0).path("actualQty").asDouble());
        assertEquals(fixture.lateStock(), recorded.get(1).path("inventoryId").asText());
        assertEquals(3.0, recorded.get(1).path("actualQty").asDouble());
        stock(fixture.soonStock(), 0, 0);
        stock(fixture.lateStock(), 2, 2);
        allocations(fixture.orderId()).andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(5.0))
            .andExpect(jsonPath("$.data.allocations[1].consumedQuantity").value(3.0))
            .andExpect(jsonPath("$.data.allocations[0].status").value("closed"))
            .andExpect(jsonPath("$.data.allocations[1].status").value("closed"));
        otherReservation(fixture);
    }

    @Test
    void anOrderCannotSpendAnotherOrdersReservationOrRecordAPartialRequest() throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.otherOrderId(), "actual");

        fefo(fixture, runId, 3).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("Only 2 kg")));

        untouched(fixture, runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"simulation", "test", "dry_run"})
    void aNonPhysicalRunCannotUseAReservationAsFreeStock(String runType) throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), runType);

        fefo(fixture, runId, 1).andExpect(status().isConflict());

        untouched(fixture, runId);
        call(get("/work-orders/" + fixture.orderId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderStatus").value("approved"));
    }

    @Test
    void aReservationDoesNotMakeAnExpiredLotUsable() throws Exception {
        Fixture fixture = fixture();
        jdbcTemplate.update("update lot_master set expiry_date = ? where lot_id = ?", LocalDate.now().minusDays(1), fixture.soonLot());
        String runId = run(fixture, fixture.orderId(), "actual");

        fefo(fixture, runId, 4).andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("Only 3 kg")));
        untouched(fixture, runId);
        JsonNode recorded = data(fefo(fixture, runId, 3));

        assertEquals(1, recorded.size());
        assertEquals(fixture.lateStock(), recorded.get(0).path("inventoryId").asText());
        stock(fixture.soonStock(), 5, 5);
        stock(fixture.lateStock(), 2, 2);
        otherReservation(fixture);
    }

    @Test
    @Timeout(45)
    void aWaitingInputSplitUsesTheCommittedReservationBalance() throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), "actual");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbcTemplate.queryForObject("select work_order_id from work_order where work_order_id = ? for update", String.class, fixture.orderId());
                jdbcTemplate.update("update stock_allocation set consumed_quantity = 1 where work_order_id = ? and inventory_id = ?",
                    fixture.orderId(), fixture.soonStock());
                jdbcTemplate.update("update inventory set quantity = quantity - 1, reserved_quantity = reserved_quantity - 1 where inventory_id = ?",
                    fixture.soonStock());
                Future<Integer> request = pool.submit(() -> fefo(fixture, runId, 7).andReturn().getResponse().getStatus());
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return request;
            });
            assertEquals(200, pending.get(15, TimeUnit.SECONDS));
            stock(fixture.soonStock(), 0, 0);
            stock(fixture.lateStock(), 2, 2);
            allocations(fixture.orderId()).andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(5.0))
                .andExpect(jsonPath("$.data.allocations[1].consumedQuantity").value(3.0));
            otherReservation(fixture);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(45)
    void anInputSplitWaitsForTheAllocationLockBeforeLockingStock(boolean concurrentSweep) throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), "actual");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?>[] sweep = new Future<?>[1];
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbcTemplate.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class, "stock-allocation|" + DEMO_PROJECT);
                Future<Integer> request = pool.submit(() -> fefo(fixture, runId, 8).andReturn().getResponse().getStatus());
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                assertFalse(request.isDone(), "FEFO must wait for the allocation transaction before taking stock.");
                if (concurrentSweep) {
                    CountDownLatch rowLocked = new CountDownLatch(1);
                    sweep[0] = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                        jdbcTemplate.queryForObject("select inventory_id from inventory where inventory_id = ? for update",
                            String.class, fixture.soonStock());
                        int sweepPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
                        rowLocked.countDown();
                        // Like the scheduled sweep, a separate transaction may briefly hold this row.
                        // Release once the allocation holder actually waits; no fixed sleep is needed.
                        DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, sweepPid);
                        return null;
                    }));
                    try {
                        assertTrue(rowLocked.await(10, TimeUnit.SECONDS), "The independent stock check did not lock its row.");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                // The lock holder can still plan against stock. Taking stock before the project lock would deadlock it.
                // A scheduled stock alert check may briefly own this row too. Bound the wait instead of
                // mistaking that independent lock for the request taking stock before allocations.
                // Reversed allocation/stock order still creates a deadlock and fails this assertion.
                jdbcTemplate.execute("set local lock_timeout = '5s'");
                jdbcTemplate.queryForObject("select inventory_id from inventory where inventory_id = ? for update", String.class, fixture.soonStock());
                return request;
            });
            assertEquals(200, pending.get(15, TimeUnit.SECONDS));
            if (sweep[0] != null) {
                sweep[0].get(15, TimeUnit.SECONDS);
            }
            stock(fixture.soonStock(), 0, 0);
            stock(fixture.lateStock(), 2, 2);
            otherReservation(fixture);
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"8.00004, 8, 2, 0, 2", "5.00004, 5, 1, 0, 5", "0.00005, 0.0001, 1, 4.9999, 5"})
    void aRoundedRequestUsesOnlyTheRequiredLotsAndKeepsReservationsConsistent(
        BigDecimal requested, BigDecimal recordedQuantity, int recordingCount, BigDecimal soonBalance, BigDecimal lateBalance
    ) throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), "actual");

        JsonNode recorded = data(fefo(fixture, runId, requested));

        assertEquals(recordingCount, recorded.size());
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode recording : recorded) {
            total = total.add(recording.path("actualQty").decimalValue());
        }
        assertEquals(0, recordedQuantity.compareTo(total));
        stock(fixture.soonStock(), soonBalance, soonBalance);
        stock(fixture.lateStock(), lateBalance, lateBalance);
        otherReservation(fixture);
    }

    @Test
    void aRequestThatRoundsToZeroReturns400WithoutRecordingOrSpendingReservations() throws Exception {
        Fixture fixture = fixture();
        String runId = run(fixture, fixture.orderId(), "actual");

        fefo(fixture, runId, new BigDecimal("0.00001")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("quantity")));

        untouched(fixture, runId);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "RES-FEFO-" + tag,
            "itemName", "Reserved FEFO " + tag, "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
        String soon = lot(itemId, tag + "-soon", 5);
        String late = lot(itemId, tag + "-late", 30);
        // Create the later-expiring stock first to distinguish expiry order from creation order.
        String lateStock = createStock(itemId, late, tag + "-late");
        String soonStock = createStock(itemId, soon, tag + "-soon");
        String orderId = order(tag + "-own");
        String otherOrderId = order(tag + "-other");
        data(call(post("/work-orders/" + orderId + "/allocations"), Map.of("lines", List.of(Map.of("itemId", itemId, "quantity", 8)))));
        data(call(post("/work-orders/" + otherOrderId + "/allocations"), Map.of("lines", List.of(Map.of("itemId", itemId, "quantity", 2)))));
        return new Fixture(itemId, soon, soonStock, lateStock, orderId, otherOrderId);
    }

    private String lot(String itemId, String lotNo, int expiresInDays) throws Exception {
        return data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "lotNo", lotNo,
            "expiryDate", LocalDate.now().plusDays(expiresInDays).toString()))).path("lotId").asText();
    }

    private String createStock(String itemId, String lotId, String location) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "lotId", lotId, "location", location, "quantity", 5))).path("inventoryId").asText();
    }

    private String order(String title) throws Exception {
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", title, "workflowId", DEMO_WORKFLOW))).path("workOrderId").asText();
        data(call(post("/work-orders/" + orderId + "/approve")));
        return orderId;
    }

    private String run(Fixture fixture, String orderId, String runType) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", orderId, "runType", runType, "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private ResultActions fefo(Fixture fixture, String runId, int quantity) throws Exception {
        return fefo(fixture, runId, BigDecimal.valueOf(quantity));
    }

    private ResultActions fefo(Fixture fixture, String runId, BigDecimal quantity) throws Exception {
        return call(post("/production-runs/" + runId + "/inputs/fefo"), Map.of("itemId", fixture.itemId(), "quantity", quantity, "unit", "kg"));
    }

    private ResultActions allocations(String orderId) throws Exception {
        return call(get("/work-orders/" + orderId + "/allocations")).andExpect(status().isOk());
    }

    private void stock(String inventoryId, int quantity, int reserved) throws Exception {
        stock(inventoryId, BigDecimal.valueOf(quantity), BigDecimal.valueOf(reserved));
    }

    private void stock(String inventoryId, BigDecimal quantity, BigDecimal reserved) throws Exception {
        call(get("/inventories/" + inventoryId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(quantity.doubleValue()))
            .andExpect(jsonPath("$.data.reservedQuantity").value(reserved.doubleValue()));
    }

    private void untouched(Fixture fixture, String runId) throws Exception {
        call(get("/production-runs/" + runId + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        stock(fixture.soonStock(), 5, 5);
        stock(fixture.lateStock(), 5, 5);
        allocations(fixture.orderId()).andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(0))
            .andExpect(jsonPath("$.data.allocations[1].consumedQuantity").value(0));
        otherReservation(fixture);
    }

    private void otherReservation(Fixture fixture) throws Exception {
        allocations(fixture.otherOrderId()).andExpect(jsonPath("$.data.allocations[0].remaining").value(2.0));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private record Fixture(String itemId, String soonLot, String soonStock, String lateStock, String orderId, String otherOrderId) {
    }
}
