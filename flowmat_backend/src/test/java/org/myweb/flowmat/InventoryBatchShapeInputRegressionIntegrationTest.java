package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@AutoConfigureMockMvc
class InventoryBatchShapeInputRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNullCountLineIsRejectedWithoutApplyingAnEarlierValidCount(boolean validLineFirst) throws Exception {
        Fixture fixture = fixture();
        DatabaseState before = state(fixture);
        ObjectNode body = countBody(fixture);
        ArrayNode lines = body.putArray("lines");
        if (validLineFirst) lines.add(countLine(fixture));
        lines.addNull();

        Reply response = call(post("/inventory-counts"), body);

        assertRejectedWithoutChanges(fixture, before, response);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNullManualPickLineIsRejectedWithoutCreatingAnyTasks(boolean validLineFirst) throws Exception {
        Fixture fixture = fixture();
        DatabaseState before = state(fixture);
        ObjectNode body = pickBody(fixture);
        ArrayNode lines = body.putArray("lines");
        if (validLineFirst) lines.add(pickLine(fixture));
        lines.addNull();

        Reply response = call(post("/warehouse-tasks/pick-list"), body);

        assertRejectedWithoutChanges(fixture, before, response);
    }

    @Test
    void aValidCountStillAdjustsStockAndRecordsItsCountHistory() throws Exception {
        Fixture fixture = fixture();
        DatabaseState before = state(fixture);
        ObjectNode body = countBody(fixture);
        body.putArray("lines").add(countLine(fixture));

        JsonNode result = success(call(post("/inventory-counts"), body));

        assertEquals(1, result.path("adjusted").asInt(), result.toString());
        assertEquals(0, result.path("unchanged").asInt(), result.toString());
        assertEquals(1, result.path("lines").size());
        JsonNode line = result.path("lines").get(0);
        assertEquals(fixture.stock(), line.path("inventoryId").asText());
        assertAmount("10", line.path("quantityBefore").decimalValue());
        assertAmount("8", line.path("countedQuantity").decimalValue());
        assertAmount("-2", line.path("difference").decimalValue());
        JsonNode stock = success(call(get("/inventories/" + fixture.stock()), null));
        assertAmount("8", stock.path("quantity").decimalValue());
        assertAmount("0", stock.path("reservedQuantity").decimalValue());
        assertAmount("8", stock.path("availableQuantity").decimalValue());
        assertEquals(DEMO_OWNER, stock.path("lastCheckedBy").asText());
        DatabaseState after = state(fixture);
        assertAmount("8", (BigDecimal) after.stock().get("quantity"));
        assertNotNull(after.stock().get("last_checked_at"));
        assertEquals(DEMO_OWNER, after.stock().get("last_checked_by"));
        assertEquals(before.movements().size() + 1, after.movements().size());
        Map<String, Object> adjustment = jdbc.queryForMap("select inventory_transaction_id,quantity_delta,reference_id "
            + "from inventory_transaction where project_id=? and reference_type='inventory_count'", fixture.project());
        assertAmount("-2", (BigDecimal) adjustment.get("quantity_delta"));
        assertEquals(result.path("countId").asText(), adjustment.get("reference_id"));
        assertEquals(line.path("inventoryTransactionId").asText(), adjustment.get("inventory_transaction_id"));
        JsonNode history = success(call(get("/inventory-counts").param("projectId", fixture.project()), null));
        assertEquals(1, history.size());
        assertEquals(result.path("countId").asText(), history.get(0).path("countId").asText());
        assertEquals(0, after.countPlans());
        assertEquals(0, after.countPlanLines());
        assertTrue(after.tasks().isEmpty());
    }

    @Test
    void aValidManualPickStillCreatesAnOpenTaskWithoutMovingStock() throws Exception {
        Fixture fixture = fixture();
        DatabaseState before = state(fixture);
        ObjectNode body = pickBody(fixture);
        body.putArray("lines").add(pickLine(fixture));

        JsonNode result = success(call(post("/warehouse-tasks/pick-list"), body));

        assertEquals(1, result.path("tasks").size(), result.toString());
        JsonNode task = result.path("tasks").get(0);
        assertEquals("pick", task.path("taskType").asText());
        assertEquals("open", task.path("status").asText());
        assertEquals(fixture.stock(), task.path("inventoryId").asText());
        assertEquals(fixture.item(), task.path("itemId").asText());
        assertEquals(fixture.source(), task.path("fromLocation").asText());
        assertEquals(fixture.staging(), task.path("toLocation").asText());
        assertAmount("3", task.path("quantity").decimalValue());
        assertTrue(task.path("workOrderId").isNull(), "This fixture exercises manual picking without a work order.");
        assertEquals(1, result.path("lines").size());
        assertAmount("3", result.path("lines").get(0).path("plannedNow").decimalValue());
        assertAmount("0", result.path("lines").get(0).path("shortage").decimalValue());
        DatabaseState after = state(fixture);
        assertEquals(before.stock(), after.stock(), "Planning a pick must leave the stock and count markers unchanged.");
        assertEquals(before.movements(), after.movements(), "A pick plan must not write a stock movement.");
        assertEquals(before.countPlans(), after.countPlans());
        assertEquals(before.countPlanLines(), after.countPlanLines());
        assertEquals(1, after.tasks().size());
        Map<String, Object> stored = after.tasks().get(0);
        assertEquals(task.path("taskId").asText(), stored.get("task_id"));
        assertEquals("open", stored.get("status"));
        assertEquals(fixture.stock(), stored.get("inventory_id"));
        assertEquals(fixture.staging(), stored.get("to_location"));
        assertAmount("3", (BigDecimal) stored.get("quantity"));
        JsonNode listed = success(call(get("/warehouse-tasks").param("projectId", fixture.project()), null));
        assertEquals(1, listed.size());
        assertEquals(task.path("taskId").asText(), listed.get(0).path("taskId").asText());
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = success(call(post("/projects"), Map.of("projectName", "Inventory batch shape " + tag,
            "ownerId", DEMO_OWNER))).path("projectId").asText();
        String item = success(call(post("/items"), Map.of("projectId", project, "itemCode", "BATCH-SHAPE-" + tag,
            "itemName", "Batch shape material", "itemType", "material", "unitId", "unit_kg", "lotManageYn", "N")))
            .path("itemId").asText();
        String source = "SOURCE-" + tag;
        String stock = success(call(post("/inventories"), Map.of("projectId", project, "itemId", item,
            "quantity", 10, "location", source))).path("inventoryId").asText();
        assertEquals(0, jdbc.queryForObject("select count(*) from storage_location where project_id=?", Integer.class, project));
        Fixture fixture = new Fixture(project, item, stock, source, "STAGE-" + tag);
        DatabaseState initial = state(fixture);
        assertAmount("10", (BigDecimal) initial.stock().get("quantity"));
        assertEquals(1, initial.movements().size(), "The fixture has only its stock creation receipt.");
        assertEquals(0, initial.countPlans());
        assertEquals(0, initial.countPlanLines());
        assertTrue(initial.tasks().isEmpty());
        return fixture;
    }

    private ObjectNode countBody(Fixture fixture) {
        return mapper.createObjectNode().put("projectId", fixture.project()).put("requestId", UUID.randomUUID().toString());
    }

    private ObjectNode countLine(Fixture fixture) {
        return mapper.createObjectNode().put("inventoryId", fixture.stock()).put("countedQuantity", 8).put("expectedQuantity", 10);
    }

    private ObjectNode pickBody(Fixture fixture) {
        return mapper.createObjectNode().put("projectId", fixture.project()).put("stagingLocation", fixture.staging());
    }

    private ObjectNode pickLine(Fixture fixture) {
        return mapper.createObjectNode().put("itemId", fixture.item()).put("quantity", 3);
    }

    private void assertRejectedWithoutChanges(Fixture fixture, DatabaseState before, Reply response) {
        assertEquals(before, state(fixture), "Invalid batch shape must not change stock, count state, ledger rows or tasks.");
        assertEquals(400, response.status(), response.body().toString());
        assertTrue(response.body().path("message").asText().contains("lines"), response.body().toString());
        assertEquals(false, response.body().path("success").asBoolean(), response.body().toString());
    }

    private DatabaseState state(Fixture fixture) {
        Map<String, Object> stock = jdbc.queryForMap("select inventory_id,item_id,lot_id,location,quantity,reserved_quantity,"
            + "available_quantity,version,last_checked_at,last_checked_by,updated_at,deleted_yn from inventory where inventory_id=?",
            fixture.stock());
        List<Map<String, Object>> movements = jdbc.queryForList("select inventory_transaction_id,inventory_id,transaction_type,"
            + "quantity_delta,quantity_after,reference_type,reference_id,request_id,created_at "
            + "from inventory_transaction where project_id=? order by inventory_transaction_id", fixture.project());
        int plans = jdbc.queryForObject("select count(*) from inventory_count_plan where project_id=?", Integer.class, fixture.project());
        int planLines = jdbc.queryForObject("select count(*) from inventory_count_plan_line l "
            + "join inventory_count_plan p on p.plan_id=l.plan_id where p.project_id=?", Integer.class, fixture.project());
        List<Map<String, Object>> tasks = jdbc.queryForList("select task_id,status,inventory_id,item_id,quantity,from_location,"
            + "to_location,work_order_id from warehouse_task where project_id=? order by task_id", fixture.project());
        return new DatabaseState(stock, movements, plans, planLines, tasks);
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString()));
    }

    private static JsonNode success(Reply reply) {
        assertEquals(200, reply.status(), reply.body().toString());
        assertTrue(reply.body().path("success").asBoolean(), reply.body().toString());
        return reply.body().path("data");
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Fixture(String project, String item, String stock, String source, String staging) {
    }

    private record DatabaseState(Map<String, Object> stock, List<Map<String, Object>> movements, int countPlans,
                                 int countPlanLines, List<Map<String, Object>> tasks) {
    }

    private record Reply(int status, JsonNode body) {
    }
}
