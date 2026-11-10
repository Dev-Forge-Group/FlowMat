package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A lost partial-completion response must not apply the same observed remaining quantity twice. */
@AutoConfigureMockMvc
class WarehouseTaskQuantityConfirmationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aLostPartialResponseCanBeReconciledBeforeAnotherMove() throws Exception {
        Fixture f = fixture();
        Map<String, Object> request = Map.of("quantity", 2, "expectedQuantity", 6);
        JsonNode done = send(DEMO_OWNER, post(f.path()), request, 200).path("data");
        assertEquals("done", done.path("status").asText());
        assertTrue(!f.task().equals(done.path("taskId").asText()));

        // The caller did not receive the first response and retries its unchanged request.
        JsonNode stale = send(DEMO_OWNER, post(f.path()), request, 409);
        assertTrue(stale.path("message").asText().contains("expectedQuantity"));
        assertState(f, "8", "4", "open", 2, 2);

        // A read resolves the uncertain result; a fresh intention uses the remaining quantity it saw.
        JsonNode tasks = send(DEMO_OWNER, get("/warehouse-tasks").param("projectId", f.project()), null, 200).path("data");
        JsonNode open = null;
        for (JsonNode task : tasks) if (f.task().equals(task.path("taskId").asText())) open = task;
        assertTrue(open != null);
        assertEquals(0, new BigDecimal("4").compareTo(open.path("quantity").decimalValue()));
        send(DEMO_OWNER, post(f.path()), Map.of("quantity", 2, "expectedQuantity", open.path("quantity").decimalValue()), 200);
        assertState(f, "6", "2", "open", 3, 4);
        send(DEMO_OWNER, post(f.path()), Map.of("expectedQuantity", 2), 200);
        assertState(f, "4", "2", "done", 3, 6);
        send(DEMO_OWNER, post(f.path()), Map.of("expectedQuantity", 2), 409);
        assertState(f, "4", "2", "done", 3, 6);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherWorkersPartialMoveInvalidatesWholeAndPartialConfirmation(boolean partial) throws Exception {
        Fixture f = fixture();
        send(DEMO_OWNER, post(f.path()), Map.of("quantity", 2), 200);
        Map<String, Object> stale = new HashMap<>(Map.of("expectedQuantity", 6));
        if (partial) stale.put("quantity", 1);
        send(DEMO_OWNER, post(f.path()), stale, 409);
        assertState(f, "8", "4", "open", 2, 2);
    }

    @Test
    void concurrentPartialRequestsForTheSameObservedQuantityMoveOnlyOnce() throws Exception {
        Fixture f = fixture();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var requests = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(30, TimeUnit.SECONDS));
                return mvc.perform(post(f.path()).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2,\"expectedQuantity\":6}"))
                    .andReturn().getResponse().getStatus();
            })).toList();
            try {
                assertTrue(ready.await(30, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            assertEquals(List.of(200, 409), requests.stream().map(result -> {
                try { return result.get(30, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
            }).sorted().toList());
        }
        assertState(f, "8", "4", "open", 2, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.00001", "10000000000"})
    void invalidConfirmationDoesNotMoveStock(String expected) throws Exception {
        Fixture f = fixture();
        send(DEMO_OWNER, post(f.path()), Map.of("quantity", 2, "expectedQuantity", new BigDecimal(expected)), 400);
        assertState(f, "10", "6", "open", 1, 0);
    }

    @Test
    void numericScaleAndLegacyRequestsRemainCompatibleAndAuthorizationStillApplies() throws Exception {
        Fixture f = fixture();
        send("unrelated-user", post(f.path()), Map.of("expectedQuantity", 6), 403);
        assertState(f, "10", "6", "open", 1, 0);
        send(DEMO_OWNER, post(f.path()), Map.of("quantity", 2, "expectedQuantity", new BigDecimal("6.00000")), 200);
        send(DEMO_OWNER, post(f.path()), null, 200);
        assertState(f, "4", "4", "done", 2, 4);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = send(DEMO_OWNER, post("/projects"), Map.of("projectName", "Quantity confirmation " + tag,
            "ownerId", DEMO_OWNER), 200).path("data").path("projectId").asText();
        String item = send(DEMO_OWNER, post("/items"), Map.of("projectId", project, "itemCode", "QTY-" + tag,
            "itemName", "Material", "unitId", "unit_kg"), 200).path("data").path("itemId").asText();
        String stock = send(DEMO_OWNER, post("/inventories"), Map.of("projectId", project, "itemId", item,
            "quantity", 10, "location", "DOCK"), 200).path("data").path("inventoryId").asText();
        String task = send(DEMO_OWNER, post("/warehouse-tasks"), Map.of("projectId", project, "inventoryId", stock,
            "quantity", 6, "toLocation", "SHELF"), 200).path("data").path("taskId").asText();
        int initialLedger = count("inventory_transaction", project);
        return new Fixture(project, stock, task, initialLedger);
    }

    private void assertState(Fixture f, String sourceQuantity, String taskQuantity, String status, int taskCount, int newLedgerRows) {
        assertEquals(0, new BigDecimal(sourceQuantity).compareTo(jdbc.queryForObject(
            "select quantity from inventory where inventory_id = ?", BigDecimal.class, f.stock())));
        assertEquals(0, new BigDecimal(taskQuantity).compareTo(jdbc.queryForObject(
            "select quantity from warehouse_task where task_id = ?", BigDecimal.class, f.task())));
        assertEquals(status, jdbc.queryForObject("select status from warehouse_task where task_id = ?", String.class, f.task()));
        assertEquals(taskCount, count("warehouse_task", f.project()));
        assertEquals(f.initialLedger() + newLedgerRows, count("inventory_transaction", f.project()));
        assertEquals(0, BigDecimal.TEN.compareTo(jdbc.queryForObject(
            "select sum(quantity) from inventory where project_id = ?", BigDecimal.class, f.project())));
    }

    private int count(String table, String project) {
        return jdbc.queryForObject("select count(*) from " + table + " where project_id = ?", Integer.class, project);
    }

    private JsonNode send(String user, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed;
    }

    private record Fixture(String project, String stock, String task, int initialLedger) {
        String path() { return "/warehouse-tasks/" + task + "/complete"; }
    }
}
