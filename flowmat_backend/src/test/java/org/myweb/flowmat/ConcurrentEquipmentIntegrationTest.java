package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Equipment that runs several work orders at once (docs/domain/equipment.md E12-E15). */
@AutoConfigureMockMvc
class ConcurrentEquipmentIntegrationTest extends IntegrationTestSupport {

    // 2041-09-02 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void ordersOverlapOnEquipmentThatRunsThemSideBySideAndAreKeptApartWhenItDoesNot() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // 10 an hour, no calendar: it can run at any hour.
        String oven = data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "OVEN-" + tag,
            "equipmentName", "tunnel oven", "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10, "concurrentWorkOrders", true))))).path("equipmentId").asText();
        call(get("/equipments/" + oven), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.concurrentWorkOrders").value(true));
        String loaf = item("LOAF-" + tag);
        String first = order("First " + tag, loaf, 20, at(8), at(10), oven);
        String second = order("Second " + tag, loaf, 20, at(8), at(10), oven);
        call(post("/work-orders/" + first + "/approve"), null).andExpect(status().isOk());
        call(post("/work-orders/" + second + "/approve"), null).andExpect(status().isOk());
        String firstNumber = data(call(get("/work-orders/" + first), null)).path("workOrderNumber").asText();

        // Side by side: the second order keeps 08-10 beside the first, readiness has no overlap, the load is not "over".
        call(get("/work-orders/" + second + "/plan-suggestion").param("from", at(8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2041-09-02T08:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-09-02T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast", hasSize(0)));
        assertFalse(codes(second).contains("schedule"));
        JsonNode row = loadRow(oven);
        assertTrue(row.path("concurrentWorkOrders").asBoolean());
        assertEquals(2.0, row.path("availableHours").asDouble());
        assertEquals(4.0, row.path("plannedHours").asDouble());
        assertEquals(200.0, row.path("loadPercent").asDouble());
        assertFalse(row.path("overloaded").asBoolean());

        // One at a time again (the details are sent whole, so leaving it out turns it off): kept apart as before.
        call(put("/equipments/" + oven), json(Map.of("details", Map.of("capacityPerHour", 10)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.concurrentWorkOrders").value(false));
        call(get("/work-orders/" + second + "/plan-suggestion").param("from", at(8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2041-09-02T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast[0]").value(firstNumber));
        assertTrue(codes(second).contains("schedule"));
        JsonNode one = loadRow(oven);
        assertFalse(one.path("concurrentWorkOrders").asBoolean());
        assertTrue(one.path("overloaded").asBoolean());

        call(put("/equipments/" + oven), json(Map.of("details", Map.of("capacityPerHour", 10, "concurrentWorkOrders", "yes"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("concurrentWorkOrders as true or false")));

        call(post("/work-orders/" + first + "/cancel"), "{}").andExpect(status().isOk());
        call(post("/work-orders/" + second + "/cancel"), "{}").andExpect(status().isOk());
    }

    private static String at(int hour) {
        return String.format("2041-09-02T%02d:00:00+09:00", hour);
    }

    private List<String> codes(String workOrderId) throws Exception {
        List<String> codes = new ArrayList<>();
        data(call(get("/work-orders/" + workOrderId + "/readiness"), null)).path("checks")
            .forEach(check -> codes.add(check.path("code").asText()));
        return codes;
    }

    private JsonNode loadRow(String equipmentId) throws Exception {
        for (JsonNode one : data(call(get("/equipment-load").param("projectId", DEMO_PROJECT).param("from", at(8))
            .param("to", at(10)), null)).path("equipment")) {
            if (equipmentId.equals(one.path("equipmentId").asText())) {
                return one;
            }
        }
        throw new AssertionError("No load row for " + equipmentId);
    }

    private String item(String code) throws Exception {
        return data(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "product", "unitId", "unit_ea")))).path("itemId").asText();
    }

    private String order(String title, String itemId, int quantity, String start, String end, String equipmentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", itemId);
        body.put("targetQuantity", quantity);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        String id = data(call(post("/work-orders"), json(body))).path("workOrderId").asText();
        call(put("/work-orders/" + id + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        return id;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
