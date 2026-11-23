package org.myweb.flowmat;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class InventoryCountPlanIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void snapshotIsServerOwnedAndOnlyOwnerCanReadBaseline() throws Exception {
        String stock = stock(); String plan = plan(List.of(stock));
        call(get(path(plan)), null, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.blind").value(true))
            .andExpect(jsonPath("$.data.lines[0].baselineQuantity").value(10.0));
        String editor = user("editor"); String viewer = user("viewer");
        for (String actor : List.of(editor, viewer)) call(get(path(plan)), null, actor).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].baselineQuantity").value(nullValue()))
            .andExpect(jsonPath("$.data.lines[0].checkpointQuantity").value(nullValue()));
        call(get(path(plan)), null, user("outsider")).andExpect(status().isForbidden());
    }

    @Test
    void submittedPlanIsAtomicAndReplayNeverAdjustsStockTwice() throws Exception {
        String stock = stock(); String plan = plan(List.of(stock));
        save(plan, line(plan, stock), 0, 12, DEMO_OWNER).andExpect(status().isOk());
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("submitted"));
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isOk());
        assertEquals(12, quantity(stock));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory_transaction where inventory_id = ? and reference_type = 'inventory_count'", Integer.class, stock));
    }

    @Test
    void changedRowsRequireRecountButUnchangedMeasurementsRemain() throws Exception {
        String first = stock(); String second = stock(); String plan = plan(List.of(first, second));
        String firstLine = line(plan, first); String secondLine = line(plan, second);
        save(plan, firstLine, 0, 10, DEMO_OWNER).andExpect(status().isOk());
        save(plan, secondLine, 0, 8, DEMO_OWNER).andExpect(status().isOk());
        jdbc.update("update inventory set quantity = 11, available_quantity = 11, version = version + 1 where inventory_id = ?", first);
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isConflict());
        JsonNode changed = data(call(get(path(plan)), null, DEMO_OWNER));
        assertEquals("recount_required", changed.path("status").asText());
        assertEquals(10, entry(changed, first).path("baselineQuantity").asInt());
        assertEquals(true, entry(changed, first).path("requiresRecount").asBoolean());
        assertEquals(8, entry(changed, second).path("countedQuantity").asInt());
        assertEquals(10, quantity(second));
        data(call(post(path(plan) + "/lines/" + firstLine + "/recount"), null, DEMO_OWNER));
        JsonNode refreshed = entry(data(call(get(path(plan)), null, DEMO_OWNER)), first);
        assertEquals(10, refreshed.path("baselineQuantity").asInt());
        assertEquals(11, refreshed.path("checkpointQuantity").asInt());
        save(plan, firstLine, refreshed.path("entryVersion").asLong(), 11, DEMO_OWNER).andExpect(status().isOk());
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isOk());
        assertEquals(11, quantity(first)); assertEquals(8, quantity(second));
    }

    @Test
    void changedVersionEvenWithOriginalQuantityRequiresRecount() throws Exception {
        String stock = stock(); String plan = plan(List.of(stock)); save(plan, line(plan, stock), 0, 9, DEMO_OWNER);
        jdbc.update("update inventory set version = version + 2 where inventory_id = ?", stock);
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(10, quantity(stock));
    }

    @Test
    void staleEntryCannotOverwriteAnotherCountersMeasurementAndRetryUsesOriginalVersion() throws Exception {
        String stock = stock(); String plan = plan(List.of(stock)); String line = line(plan, stock);
        save(plan, line, 0, 9, DEMO_OWNER).andExpect(status().isOk());
        save(plan, line, 0, 9, DEMO_OWNER).andExpect(status().isOk());
        save(plan, line, 0, 8, DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(9, entry(data(call(get(path(plan)), null, DEMO_OWNER)), stock).path("countedQuantity").asInt());
    }

    @Test
    void reservationFailureDoesNotPartiallyApplyThePlan() throws Exception {
        String first = stock(); String second = stock(); String plan = plan(List.of(first, second));
        save(plan, line(plan, first), 0, 8, DEMO_OWNER); save(plan, line(plan, second), 0, 1, DEMO_OWNER);
        jdbc.update("update inventory set reserved_quantity = 3, available_quantity = 7 where inventory_id = ?", second);
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(10, quantity(first)); assertEquals(10, quantity(second));
        assertEquals("open", data(call(get(path(plan)), null, DEMO_OWNER)).path("status").asText());
    }

    @Test
    void createRetryReusesSnapshotAndDifferentKeyContentsConflict() throws Exception {
        String stock = stock(); Map<String,Object> body = Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "blind", true, "inventoryIds", List.of(stock));
        String plan = data(call(post("/inventory-count-plans"), body, DEMO_OWNER)).path("planId").asText();
        jdbc.update("update inventory set quantity = 11, available_quantity = 11, version = version + 1 where inventory_id = ?", stock);
        call(post("/inventory-count-plans"), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.planId").value(plan)).andExpect(jsonPath("$.data.lines[0].baselineQuantity").value(10.0));
        call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", body.get("requestId"),
            "blind", false, "inventoryIds", List.of(stock)), DEMO_OWNER).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "outsider"})
    void readersAndOutsidersCannotCreateOrRecordPlans(String role) throws Exception {
        String stock = stock(); String plan = plan(List.of(stock)); String actor = user(role);
        call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "inventoryIds", List.of(stock), "blind", true), actor).andExpect(status().isForbidden());
        save(plan, line(plan, stock), 0, 10, actor).andExpect(status().isForbidden());
        call(post(path(plan) + "/submit"), null, actor).andExpect(status().isForbidden());
    }

    @Test
    void missingCountAndWrongPlanLineAreRejected() throws Exception {
        String first = stock(); String plan = plan(List.of(first));
        call(post(path(plan) + "/submit"), null, DEMO_OWNER).andExpect(status().isBadRequest());
        String other = plan(List.of(stock()));
        save(other, line(plan, first), 0, 2, DEMO_OWNER).andExpect(status().isNotFound());
        save(plan, line(plan, first), 0, -1, DEMO_OWNER).andExpect(status().isBadRequest());
        assertEquals(10, quantity(first));
    }

    @Test
    @Timeout(45)
    void concurrentSubmitAppliesOneCountOnly() throws Exception {
        String stock = stock(); String plan = plan(List.of(stock));
        save(plan, line(plan, stock), 0, 12, DEMO_OWNER).andExpect(status().isOk());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> call(post(path(plan) + "/submit"), null, DEMO_OWNER).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> call(post(path(plan) + "/submit"), null, DEMO_OWNER).andReturn().getResponse().getStatus());
            assertEquals(200, first.get(15, TimeUnit.SECONDS)); assertEquals(200, second.get(15, TimeUnit.SECONDS));
            assertEquals(12, quantity(stock));
            assertEquals(1, jdbc.queryForObject("select count(*) from inventory_transaction where inventory_id = ? and reference_type = 'inventory_count'", Integer.class, stock));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void editorCanCountWithoutReceivingBaselineEvenOnANonblindPlan() throws Exception {
        String stock = stock(); String editor = user("editor");
        JsonNode created = data(call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT,
            "requestId", UUID.randomUUID().toString(), "blind", false, "inventoryIds", List.of(stock)), editor));
        assertEquals(true, created.path("lines").get(0).path("baselineQuantity").isNull());
        String plan = created.path("planId").asText();
        save(plan, line(plan, stock), 0, 10, editor).andExpect(status().isOk());
        call(post(path(plan) + "/submit"), null, editor).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].baselineQuantity").value(nullValue()));
        save(plan, line(plan, stock), 1, 9, editor).andExpect(status().isConflict());
        assertEquals(10, quantity(stock));
    }

    @Test
    void invalidBodiesCannotCreatePlansOrCorruptMeasurements() throws Exception {
        String stock = stock();
        call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", "not-a-uuid",
            "blind", true, "inventoryIds", List.of(stock)), DEMO_OWNER).andExpect(status().isBadRequest());
        call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "blind", true, "inventoryIds", List.of(stock, stock)), DEMO_OWNER).andExpect(status().isBadRequest());
        call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "blind", true, "note", "bad\u0000note", "inventoryIds", List.of(stock)), DEMO_OWNER).andExpect(status().isBadRequest());
        String plan = plan(List.of(stock)); String row = line(plan, stock);
        call(put(path(plan) + "/lines/" + row), Map.of("expectedEntryVersion", 0, "countedQuantity", 10000000000L), DEMO_OWNER)
            .andExpect(status().isBadRequest());
        call(put(path(plan) + "/lines/" + row), Map.of("expectedEntryVersion", -1, "countedQuantity", 1), DEMO_OWNER)
            .andExpect(status().isBadRequest());
        call(put(path(plan) + "/lines/" + row), Map.of("expectedEntryVersion", 0, "countedQuantity", new java.math.BigDecimal("1.23456")), DEMO_OWNER)
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].countedQuantity").value(1.2346));
        assertEquals(10, quantity(stock));
    }

    @Test
    @Timeout(45)
    void concurrentReuseOfOneCreateKeyReturnsConflictForDifferentStockSets() throws Exception {
        String firstStock = stock(); String secondStock = stock(); String requestId = UUID.randomUUID().toString();
        Map<String,Object> firstBody = Map.of("projectId", DEMO_PROJECT, "requestId", requestId, "blind", true, "inventoryIds", List.of(firstStock));
        Map<String,Object> secondBody = Map.of("projectId", DEMO_PROJECT, "requestId", requestId, "blind", true, "inventoryIds", List.of(secondStock));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> call(post("/inventory-count-plans"), firstBody, DEMO_OWNER).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> call(post("/inventory-count-plans"), secondBody, DEMO_OWNER).andReturn().getResponse().getStatus());
            var results = java.util.stream.Stream.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).sorted().toList();
            assertEquals(List.of(200, 409), results);
            assertEquals(1, jdbc.queryForObject("select count(*) from inventory_count_plan where project_id = ? and request_id = ?::uuid", Integer.class, DEMO_PROJECT, requestId));
        } finally { pool.shutdownNow(); }
    }

    private String stock() throws Exception {
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "COUNT-" + UUID.randomUUID().toString().substring(0,8),
            "itemName", "Count plan", "itemType", "material", "resourceCategory", "material", "unitId", "unit_kg"), DEMO_OWNER)).path("itemId").asText();
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "quantity", 10,
            "location", "COUNT-PLAN", "inventoryStatus", "available"), DEMO_OWNER)).path("inventoryId").asText();
    }
    private String plan(List<String> ids) throws Exception {
        return data(call(post("/inventory-count-plans"), Map.of("projectId", DEMO_PROJECT, "requestId", UUID.randomUUID().toString(),
            "blind", true, "inventoryIds", ids), DEMO_OWNER)).path("planId").asText();
    }
    private String line(String plan, String stock) throws Exception {
        return entry(data(call(get(path(plan)), null, DEMO_OWNER)), stock).path("lineId").asText();
    }
    private JsonNode entry(JsonNode plan, String stock) {
        for (JsonNode row : plan.path("lines")) if (row.path("inventoryId").asText().equals(stock)) return row;
        throw new AssertionError("Missing inventory count row");
    }
    private ResultActions save(String plan, String line, long version, int qty, String actor) throws Exception {
        return call(put(path(plan) + "/lines/" + line), Map.of("expectedEntryVersion", version, "countedQuantity", qty), actor);
    }
    private int quantity(String stock) { return jdbc.queryForObject("select quantity from inventory where inventory_id = ?", Integer.class, stock); }
    private String user(String role) {
        String id = "count-plan-" + UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id = ?", id, id+"@test.local", DEMO_OWNER);
        if (!role.equals("outsider")) jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)", UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }
    private String path(String plan) { return "/inventory-count-plans/" + plan; }
    private ResultActions call(MockHttpServletRequestBuilder request, Map<String,?> body, String actor) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
}
