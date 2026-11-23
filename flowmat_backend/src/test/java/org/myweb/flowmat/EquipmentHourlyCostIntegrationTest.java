package org.myweb.flowmat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.global.security.JwtProvider;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@AutoConfigureMockMvc
class EquipmentHourlyCostIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EquipmentCostQuery query;

    @Test
    void currentRateSupportsExplicitZeroClearAndSafeRetryWithoutChangingOtherDetails() throws Exception {
        String equipment = equipment();
        rate(equipment).andExpect(jsonPath("$.data.hourlyCost").isEmpty()).andExpect(jsonPath("$.data.version").value(0));
        save(equipment, "{\"hourlyCost\":12.3456,\"expectedVersion\":0}")
            .andExpect(jsonPath("$.data.hourlyCost").value(12.3456)).andExpect(jsonPath("$.data.version").value(1));
        // A lost response can be replayed by the same author, with no second version increment.
        save(equipment, "{\"hourlyCost\":12.3456,\"expectedVersion\":0}").andExpect(jsonPath("$.data.version").value(1));
        call(put("/equipments/" + equipment), "{\"details\":{\"capacityPerHour\":20}}")
            .andExpect(status().isOk());
        rate(equipment).andExpect(jsonPath("$.data.hourlyCost").value(12.3456));
        save(equipment, "{\"hourlyCost\":0,\"expectedVersion\":1}").andExpect(jsonPath("$.data.version").value(2));
        save(equipment, "{\"hourlyCost\":null,\"expectedVersion\":2}")
            .andExpect(jsonPath("$.data.hourlyCost").isEmpty()).andExpect(jsonPath("$.data.version").value(3));
        assertFalse(query.findHourlyCosts(DEMO_PROJECT, java.util.List.of(equipment)).containsKey(equipment));
        save(equipment, "{\"hourlyCost\":9,\"expectedVersion\":1}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("expectedVersion")));
    }

    @Test
    void invalidInputAndForeignEquipmentCannotChangeTheRate() throws Exception {
        String equipment = equipment();
        for (String cost : new String[] {"-1", "0.00001", "10000000000"})
            save(equipment, "{\"hourlyCost\":" + cost + ",\"expectedVersion\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("hourlyCost")));
        for (String body : new String[] {"{}", "{\"hourlyCost\":3}", "{\"expectedVersion\":0}",
            "{\"hourlyCost\":\"wrong\",\"expectedVersion\":0}", "{\"hourlyCost\":3,\"expectedVersion\":-1}",
            "{\"hourlyCost\":3,\"expectedVersion\":1.5}"})
            save(equipment, body).andExpect(status().isBadRequest());
        callAs("unrelated-user", get("/equipments/" + equipment + "/hourly-cost"), null).andExpect(status().isForbidden());
        callAs("unrelated-user", put("/equipments/" + equipment + "/hourly-cost"), "{\"hourlyCost\":3,\"expectedVersion\":0}")
            .andExpect(status().isForbidden());
        rate("missing-equipment").andExpect(status().isNotFound());
        call(delete("/equipments/" + equipment), null).andExpect(status().isOk());
        rate(equipment).andExpect(status().isNotFound());
        save(equipment, "{\"hourlyCost\":3,\"expectedVersion\":0}").andExpect(status().isNotFound());
    }

    @Test
    @Timeout(20)
    void twoEditorsOfTheSameLoadedVersionDoNotOverwriteEachOther() throws Exception {
        String equipment = equipment();
        var gate = new CyclicBarrier(3);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int cost : new int[] {30, 40}) results.add(workers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return save(equipment, "{\"hourlyCost\":" + cost + ",\"expectedVersion\":0}")
                    .andReturn().getResponse().getStatus();
            }));
            gate.await(10, TimeUnit.SECONDS);
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(java.util.List.of(200,409), statuses);
        }
        rate(equipment).andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void loadUsesExactMinutesAndCurrentRateAsASeparateEstimateIncludingUnknownAndZero() throws Exception {
        String equipment = equipment();
        String first = item(), next = item();
        call(post("/equipments/" + equipment + "/changeovers"), json.writeValueAsString(Map.of(
            "fromItemId", first, "toItemId", next, "minutes", 1))).andExpect(status().isOk());
        order(equipment, first, "2030-01-07T09:00:00+09:00", "2030-01-07T17:00:00+09:00");
        String second = order(equipment, next, "2030-01-08T09:00:00+09:00", "2030-01-08T17:00:00+09:00");
        String third = order(equipment, next, "2030-01-09T09:00:00+09:00", "2030-01-09T17:00:00+09:00");
        JsonNode unknown = data(load()).path("equipment");
        assertTrue(findOrder(unknown, second).has("setupCostEstimate"));
        assertTrue(findOrder(unknown, second).get("setupCostEstimate").isNull());
        save(equipment, "{\"hourlyCost\":60,\"expectedVersion\":0}").andExpect(status().isOk());
        JsonNode loaded = data(load()).path("equipment");
        assertEquals(1, findOrder(loaded, second).path("setupCostEstimate").decimalValue().intValueExact());
        assertEquals(0, findOrder(loaded, third).path("setupCostEstimate").decimalValue().signum());
        // 1 minute is 1/60 h, not the board's rounded 0.02 h used to display capacity.
        assertNotEquals(1.2, findOrder(loaded, second).path("setupCostEstimate").asDouble());
        save(equipment, "{\"hourlyCost\":0,\"expectedVersion\":1}").andExpect(status().isOk());
        assertEquals(0, findOrder(data(load()).path("equipment"), second).path("setupCostEstimate").decimalValue().signum());
    }

    @Test
    void viewerReadsEditorWritesAndPublicRatesAreConfinedToLiveProjectEquipment() throws Exception {
        String equipment = equipment();
        String viewer = user("viewer"), editor = user("editor");
        callAs(viewer, get("/equipments/"+equipment+"/hourly-cost"), null).andExpect(status().isOk());
        callAs(viewer, put("/equipments/"+equipment+"/hourly-cost"), "{\"hourlyCost\":3,\"expectedVersion\":0}")
            .andExpect(status().isForbidden());
        callAs(editor, put("/equipments/"+equipment+"/hourly-cost"), "{\"hourlyCost\":0,\"expectedVersion\":0,\"updatedBy\":\"unrelated-user\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedBy").value(editor));
        // Another author cannot present the previous author's save as their own lost-response replay.
        save(equipment, "{\"hourlyCost\":0,\"expectedVersion\":0}").andExpect(status().isConflict());
        assertEquals(0, query.findHourlyCosts(DEMO_PROJECT, java.util.List.of(equipment)).get(equipment).signum());
        assertTrue(query.findHourlyCosts("foreign-project", java.util.List.of(equipment)).isEmpty());
        assertNull(query.findHourlyCosts(DEMO_PROJECT, java.util.List.of()).get(null));
        assertNull(query.findHourlyCosts(DEMO_PROJECT, null).get(null));
        call(delete("/equipments/"+equipment), null).andExpect(status().isOk());
        assertTrue(query.findHourlyCosts(DEMO_PROJECT, java.util.List.of(equipment)).isEmpty());
    }

    @Test
    void malformedJsonAndExhaustedVersionsLeaveTheLastRateIntact() throws Exception {
        String equipment = equipment();
        save(equipment, "{").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("hourlyCost")));
        save(equipment, "{\"hourlyCost\":9999999999.9999,\"expectedVersion\":0}").andExpect(status().isOk());
        jdbc.update("update equipment_hourly_cost set version=? where equipment_id=?", Long.MAX_VALUE, equipment);
        save(equipment, "{\"hourlyCost\":1,\"expectedVersion\":"+Long.MAX_VALUE+"}").andExpect(status().isConflict());
        rate(equipment).andExpect(jsonPath("$.data.hourlyCost").value(9999999999.9999));
    }

    private String user(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",
            id, id+"@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)", UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }

    private JsonNode findOrder(JsonNode equipment, String id) {
        for (JsonNode row : equipment) for (JsonNode order : row.path("orders"))
            if (id.equals(order.path("workOrderId").asText())) return order;
        throw new AssertionError("Missing work order " + id);
    }
    private ResultActions load() throws Exception {
        return call(get("/equipment-load").param("projectId", DEMO_PROJECT)
            .param("from", "2030-01-07T00:00:00+09:00").param("to", "2030-01-14T00:00:00+09:00"), null);
    }
    private String order(String equipment, String item, String from, String to) throws Exception {
        String id = data(call(post("/work-orders"), json.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Setup " + UUID.randomUUID(), "targetItemId", item, "targetQuantity", 10,
            "plannedStartAt", from, "plannedEndAt", to)))).path("workOrderId").asText();
        call(put("/work-orders/" + id + "/equipment"), json.writeValueAsString(Map.of("equipmentId",equipment))).andExpect(status().isOk());
        call(post("/work-orders/" + id + "/approve"), null).andExpect(status().isOk());
        return id;
    }
    private String item() throws Exception {
        return data(call(post("/items"), json.writeValueAsString(Map.of("projectId", DEMO_PROJECT, "itemCode", UUID.randomUUID().toString(),
            "itemName", "Setup item", "itemType", "product", "unitId", "unit_ea")))).path("itemId").asText();
    }
    private String equipment() throws Exception {
        return data(call(post("/equipments"), json.writeValueAsString(Map.of("projectId",DEMO_PROJECT,"equipmentName","Setup equipment",
            "equipmentType","machine","details",Map.of("capacityPerHour",10))))).path("equipmentId").asText();
    }
    private ResultActions rate(String id) throws Exception { return call(get("/equipments/"+id+"/hourly-cost"),null); }
    private ResultActions save(String id,String body) throws Exception { return call(put("/equipments/"+id+"/hourly-cost"),body); }
    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private ResultActions call(MockHttpServletRequestBuilder request,String body) throws Exception { return callAs(DEMO_OWNER,request,body); }
    private ResultActions callAs(String actor,MockHttpServletRequestBuilder request,String body) throws Exception {
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request.header("Authorization","Bearer "+jwt.generateAccessToken(actor)));
    }
}
