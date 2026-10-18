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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Side-by-side equipment with a limit on how many work orders it takes at once (docs/domain/equipment.md E16-E19). */
@AutoConfigureMockMvc
class ConcurrentEquipmentLimitIntegrationTest extends IntegrationTestSupport {
    private static final String LIMIT = "concurrentLimit is a whole number from 2 to 99.";

    // 2041-09-02 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void equipmentTakesUpToItsLimitAtOnceAndKeepsTheNextApart() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // 10 an hour, no calendar: it can run at any hour; two at once.
        String oven = data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "TWIN-" + tag,
            "equipmentName", "twin oven", "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10, "concurrentWorkOrders", true, "concurrentLimit", 2))))).path("equipmentId").asText();
        call(get("/equipments/" + oven), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.concurrentWorkOrders").value(true))
            .andExpect(jsonPath("$.data.details.concurrentLimit").value(2));
        String loaf = item("LOAF-" + tag);
        String first = order("First " + tag, loaf, 20, at(8), at(10), oven);
        String second = order("Second " + tag, loaf, 30, at(8), at(11), oven);
        String third = order("Third " + tag, loaf, 20, at(8), at(10), oven);
        call(post("/work-orders/" + first + "/approve"), null).andExpect(status().isOk());
        call(post("/work-orders/" + second + "/approve"), null).andExpect(status().isOk());
        String firstNumber = data(call(get("/work-orders/" + first), null)).path("workOrderNumber").asText();

        // Two fit beside each other; a third waits until the first of them ends.
        call(get("/work-orders/" + second + "/plan-suggestion").param("from", at(8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2041-09-02T08:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast", hasSize(0)));
        call(get("/work-orders/" + third + "/plan-suggestion").param("from", at(8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2041-09-02T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-09-02T12:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast", hasSize(1)))
            .andExpect(jsonPath("$.data.movedPast[0]").value(firstNumber));
        assertFalse(checks(second).containsKey("schedule"));
        assertTrue(checks(third).getOrDefault("schedule", "").contains("runs at most 2 work orders at once"),
            checks(third).toString());

        // The load counts two orders' hours: 4 planned of 2 x 2 available is full, not over.
        JsonNode row = loadRow(oven);
        assertEquals(2, row.path("concurrentLimit").asInt());
        assertEquals(2.0, row.path("availableHours").asDouble());
        assertEquals(4.0, row.path("plannedHours").asDouble());
        assertEquals(100.0, row.path("loadPercent").asDouble());
        assertFalse(row.path("overloaded").asBoolean());
        call(post("/work-orders/" + third + "/approve"), null).andExpect(status().isOk());
        JsonNode three = loadRow(oven);
        assertEquals(150.0, three.path("loadPercent").asDouble());
        assertTrue(three.path("overloaded").asBoolean());

        // Without a limit, as before (E12-E14).
        JsonNode unlimited = data(call(put("/equipments/" + oven),
            json(Map.of("details", Map.of("capacityPerHour", 10, "concurrentWorkOrders", true)))));
        assertTrue(unlimited.path("details").path("concurrentLimit").isNull()
            || unlimited.path("details").path("concurrentLimit").isMissingNode());
        JsonNode open = loadRow(oven);
        assertEquals(300.0, open.path("loadPercent").asDouble());
        assertFalse(open.path("overloaded").asBoolean());

        for (Object wrong : List.of(1, 100)) {
            refused(oven, Map.of("capacityPerHour", 10, "concurrentWorkOrders", true, "concurrentLimit", wrong), LIMIT);
        }
        for (String wrong : List.of("2.5", "\"3\"")) {
            call(put("/equipments/" + oven), "{\"details\":{\"concurrentWorkOrders\":true,\"concurrentLimit\":" + wrong + "}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LIMIT));
        }
        refused(oven, Map.of("capacityPerHour", 10, "concurrentLimit", 2), "concurrentLimit needs concurrentWorkOrders.");
        call(put("/equipments/" + oven), json(Map.of("details", Map.of("capacityPerHour", 10, "concurrentWorkOrders", "yes"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("concurrentWorkOrders as true or false")));

        for (String id : List.of(first, second, third)) {
            call(post("/work-orders/" + id + "/cancel"), "{}").andExpect(status().isOk());
        }
    }

    private void refused(String equipmentId, Map<String, Object> details, String message) throws Exception {
        call(put("/equipments/" + equipmentId), json(Map.of("details", details)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private static String at(int hour) {
        return String.format("2041-09-02T%02d:00:00+09:00", hour);
    }

    /** The readiness checks by code, each with its message. */
    private Map<String, String> checks(String workOrderId) throws Exception {
        Map<String, String> checks = new HashMap<>();
        data(call(get("/work-orders/" + workOrderId + "/readiness"), null)).path("checks")
            .forEach(check -> checks.put(check.path("code").asText(), check.path("message").asText()));
        return checks;
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
