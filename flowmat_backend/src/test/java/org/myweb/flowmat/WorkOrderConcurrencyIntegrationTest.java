package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class WorkOrderConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource({"edit, draft, approved, 400", "approve, draft, approved, 400", "cancel, approved, in_progress, 400",
        "complete, in_progress, completed, 400", "start, approved, cancelled, 400",
        "equipment, approved, completed, 409", "allocate, approved, cancelled, 409"})
    @Timeout(45)
    void aWaitingMutationUsesTheCommittedOrderState(String operation, String before, String after, int expectedStatus) throws Exception {
        Fixture fixture = fixture();
        jdbcTemplate.update("update work_order set work_order_status = ? where work_order_id = ?", before, fixture.orderId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                holdOrder(fixture.orderId());
                jdbcTemplate.update("update work_order set work_order_status = ? where work_order_id = ?", after, fixture.orderId());
                Future<Integer> request = pool.submit(() -> mutate(operation, fixture));
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return request;
            });
            assertEquals(expectedStatus, pending.get(15, TimeUnit.SECONDS));
            call(get("/work-orders/" + fixture.orderId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderStatus").value(after))
                .andExpect(jsonPath("$.data.workOrderTitle").value(fixture.title()))
                .andExpect(jsonPath("$.data.runCount").value(0));
            call(get("/inventories/" + fixture.stockId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(10.0))
                .andExpect(jsonPath("$.data.reservedQuantity").value(0.0));
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"single_release", "all_release", "consume", "allocate", "cancel"})
    @Timeout(45)
    void aWaitingReservationMutationKeepsTheCommittedConsumption(String operation) throws Exception {
        Fixture fixture = fixture();
        data(call(post("/work-orders/" + fixture.orderId() + "/approve")));
        String allocationId = data(call(post("/work-orders/" + fixture.orderId() + "/allocations"), lines(fixture.itemId(), 4)))
            .path("allocations").get(0).path("allocationId").asText();
        // An unrelated order owns another 4 kg of the same record; it must stay reserved.
        String other = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Other " + fixture.title(), "workflowId", fixture.workflowId(), "targetQuantity", 1)))
            .path("workOrderId").asText();
        data(call(post("/work-orders/" + other + "/approve")));
        data(call(post("/work-orders/" + other + "/allocations"), lines(fixture.itemId(), 4)));
        String runId = "consume".equals(operation) ? data(call(post("/production-runs/start"), start(fixture)))
            .path("productionRunId").asText() : null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                holdOrder(fixture.orderId());
                // A concurrent input has used 1 kg of this allocation and stock, still uncommitted.
                jdbcTemplate.update("update stock_allocation set consumed_quantity = 1 where allocation_id = ?", allocationId);
                jdbcTemplate.update("update inventory set quantity = quantity - 1, reserved_quantity = reserved_quantity - 1 "
                    + "where inventory_id = ?", fixture.stockId());
                Future<Integer> request = pool.submit(() -> reservationMutation(operation, fixture, allocationId, runId));
                DatabaseContention.awaitWaitingOrDone(jdbcTemplate, request, jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                return request;
            });
            assertEquals(200, pending.get(15, TimeUnit.SECONDS));
            JsonNode allocation = data(call(get("/work-orders/" + fixture.orderId() + "/allocations")))
                .path("allocations").get(0);
            assertEquals("consume".equals(operation) ? 3.0 : 1.0, allocation.path("consumedQuantity").asDouble());
            boolean release = "single_release".equals(operation) || "all_release".equals(operation) || "cancel".equals(operation);
            assertEquals(release ? 3.0 : 0.0, allocation.path("releasedQuantity").asDouble());
            assertEquals(release ? "closed" : "open", allocation.path("status").asText());
            call(get("/inventories/" + fixture.stockId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value("consume".equals(operation) ? 7.0 : 9.0))
                .andExpect(jsonPath("$.data.reservedQuantity").value(release ? 4.0 : "consume".equals(operation) ? 5.0 : 8.0));
            call(get("/work-orders/" + other + "/allocations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocations[0].remaining").value(4.0));
        } finally {
            pool.shutdownNow();
        }
    }

    private void holdOrder(String orderId) {
        jdbcTemplate.queryForObject("select work_order_id from work_order where work_order_id = ? for update", String.class, orderId);
    }

    private int mutate(String operation, Fixture fixture) throws Exception {
        String base = "/work-orders/" + fixture.orderId();
        ResultActions result = switch (operation) {
            case "edit" -> call(put(base), Map.of("workOrderTitle", "Changed after approval"));
            case "approve", "cancel", "complete" -> call(post(base + "/" + operation));
            case "start" -> call(post("/production-runs/start"), start(fixture));
            case "equipment" -> call(put(base + "/equipment"), Map.of());
            case "allocate" -> call(post(base + "/allocations"), lines(fixture.itemId(), 2));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private int reservationMutation(String operation, Fixture fixture, String allocationId, String runId) throws Exception {
        String base = "/work-orders/" + fixture.orderId() + "/allocations";
        ResultActions result = switch (operation) {
            case "single_release" -> call(post(base + "/" + allocationId + "/release"));
            case "all_release" -> call(post(base + "/release"));
            case "cancel" -> call(post("/work-orders/" + fixture.orderId() + "/cancel"));
            case "allocate" -> call(post(base), lines(fixture.itemId(), 4));
            case "consume" -> call(post("/production-runs/" + runId + "/items"), Map.of("itemId", fixture.itemId(),
                "inventoryId", fixture.stockId(), "direction", "input", "plannedQty", 2, "actualQty", 2, "unit", "kg"));
            default -> throw new IllegalArgumentException(operation);
        };
        return result.andReturn().getResponse().getStatus();
    }

    private Map<String, ?> start(Fixture fixture) {
        return Map.of("projectId", DEMO_PROJECT, "workflowId", fixture.workflowId(), "workOrderId", fixture.orderId(), "plannedOutputQty", 1);
    }

    private Map<String, ?> lines(String itemId, int quantity) {
        return Map.of("lines", List.of(Map.of("itemId", itemId, "quantity", quantity)));
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String title = "Order race " + tag;
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "ORDER-RACE-" + tag,
            "itemName", title, "unitId", "unit_kg"))).path("itemId").asText();
        String stockId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "location", "ORDER-RACE-" + tag, "quantity", 10))).path("inventoryId").asText();
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", title)))
            .path("workflowId").asText();
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", workflowId, "targetQuantity", 1))).path("workOrderId").asText();
        return new Fixture(orderId, workflowId, itemId, stockId, title);
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

    private record Fixture(String orderId, String workflowId, String itemId, String stockId, String title) {
    }
}
