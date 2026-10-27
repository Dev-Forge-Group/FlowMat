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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
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

/** SL5 compares the committed item and location conditions after a new LOT stock record waits for its stock-place lock. */
@AutoConfigureMockMvc
@Timeout(60)
class StorageConditionChangeDuringReceiptIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryRepository inventories;
    @Autowired private PlatformTransactionManager transactions;

    @ParameterizedTest(name = "{0} condition changed to {1}: waiting receipt returns {2}")
    @CsvSource({"location,Frozen,409", "location,CHILLED,200", "item,Frozen,409", "item,CHILLED,200"})
    void aWaitingReceiptUsesTheChangedConditionAndRefusalLeavesNoStockOrMovement(
        String target, String condition, int expectedStatus) throws Exception {
        Fixture fixture = fixture();
        assertCounts(fixture, 0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate gate = new TransactionTemplate(transactions);
            gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Future<Reply> pending = gate.execute(transaction -> {
                // This is the same item/LOT/place lock Add Stock takes after resolving its listed location.
                inventories.lockStockPlace(fixture.project(), fixture.item(), fixture.lot(), "COLD");
                Integer blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                assertNotNull(blocker);
                Future<Reply> waiting = pool.submit(() -> call(fixture.owner(), post("/inventories"), Map.of(
                    "projectId", fixture.project(), "itemId", fixture.item(), "lotId", fixture.lot(), "quantity", 5, "location", "COLD")));
                awaitStockPlaceLock(waiting, blocker);
                assertCounts(fixture, 0);
                try {
                    if ("location".equals(target)) {
                        JsonNode changed = ok(call(fixture.owner(), put("/storage-locations/" + fixture.location()),
                            Map.of("storageCondition", condition)));
                        assertEquals(condition, changed.path("storageCondition").asText());
                    } else {
                        JsonNode changed = ok(call(fixture.owner(), put("/items/" + fixture.item()),
                            Map.of("details", Map.of("storageCondition", condition))));
                        assertEquals(condition, changed.path("details").path("storageCondition").asText());
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("Changing the isolated storage condition failed.", exception);
                }
                return waiting;
            });
            assertNotNull(pending);
            Reply receipt = pending.get(20, TimeUnit.SECONDS);
            assertEquals(expectedStatus, receipt.status(), receipt.message());
            assertEquals("location".equals(target) ? condition : "Chilled",
                jdbc.queryForObject("select storage_condition from storage_location where location_id=?",
                String.class, fixture.location()));
            assertEquals("item".equals(target) ? condition : "Chilled",
                jdbc.queryForObject("select storage_condition from item where item_id=?", String.class, fixture.item()));

            if (expectedStatus == 409) {
                assertTrue(receipt.message().contains("refuses stock"), receipt.message());
                assertCounts(fixture, 0);
                assertEquals(0, ok(call(fixture.owner(), get("/inventories").param("projectId", fixture.project()), null)).size());
            } else {
                assertCounts(fixture, 1);
                JsonNode stock = receipt.data();
                assertEquals(fixture.lot(), stock.path("lotId").asText());
                assertEquals("COLD", stock.path("location").asText());
                assertEquals(5, stock.path("quantity").asInt());
                assertEquals(1, jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?"
                    + " and inventory_id=? and transaction_type='receipt' and quantity_delta=5", Integer.class,
                    fixture.project(), stock.path("inventoryId").asText()));
            }
        } finally {
            // Gate commit/rollback releases the advisory lock before the worker is joined here.
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "The waiting stock receipt did not terminate.");
        }
    }

    private void awaitStockPlaceLock(Future<?> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.isDone(), "The receipt must reach the held stock-place lock before the condition changes.");
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_locks"
                + " where locktype='advisory' and not granted and ? = any(pg_blocking_pids(pid)))", Boolean.class, blocker))) {
                return;
            }
            try {
                // Polling only: pg_locks and pg_blocking_pids prove the actual advisory-lock wait.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the receipt's stock-place lock.", exception);
            }
        }
        fail("The receipt did not reach the held item/LOT/place advisory lock.");
    }

    private Fixture fixture() throws Exception {
        String owner = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel)"
            + " select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            owner, owner + "@test.local", DEMO_OWNER));
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = ok(call(owner, post("/projects"), Map.of("projectName", "Receipt condition race " + tag, "ownerId", owner)))
            .path("projectId").asText();
        String location = ok(call(owner, post("/storage-locations"), Map.of("projectId", project, "locationCode", "COLD",
            "locationType", "warehouse", "storageCondition", "Chilled"))).path("locationId").asText();
        String item = ok(call(owner, post("/items"), Map.of("projectId", project, "itemCode", "CONDITION-" + tag,
            "itemName", "Chilled receipt material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "Y",
            "details", Map.of("storageCondition", "Chilled")))).path("itemId").asText();
        String lot = ok(call(owner, post("/lots"), Map.of("projectId", project, "itemId", item, "lotNo", "LOT-" + tag)))
            .path("lotId").asText();
        Map<String, Object> setting = new HashMap<>();
        setting.put("projectId", project);
        setting.put("storageConditionPolicy", "refuse");
        setting.put("expectedVersion", null);
        assertEquals("refuse", ok(call(owner, put("/inventory-settings"), setting)).path("storageConditionPolicy").asText());
        return new Fixture(owner, project, location, item, lot);
    }

    private void assertCounts(Fixture fixture, int expected) {
        assertEquals(expected, jdbc.queryForObject("select count(*) from inventory where project_id=?", Integer.class, fixture.project()));
        assertEquals(expected, jdbc.queryForObject("select count(*) from inventory_transaction where project_id=?", Integer.class, fixture.project()));
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

    private record Fixture(String owner, String project, String location, String item, String lot) {
    }

    private record Reply(int status, JsonNode data, String message) {
    }
}
