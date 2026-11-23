package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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

/** Equipment efficiency (docs/domain/equipment.md E8-E11) in plan suggestions, the load board, readiness and the comparison. */
@AutoConfigureMockMvc
class EquipmentEfficiencyIntegrationTest extends IntegrationTestSupport {

    // 2041-08-05 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY = "2041-08-05T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-08-12T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void plansReckonAtCapacityTimesEfficiencyAndChangeoversStayAsTheyAre() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // 10 an hour rated, 80% reached: 8 an hour. No calendar, so the press can run at any hour.
        String press = data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "EFF-" + tag,
            "equipmentName", "efficient press", "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10, "efficiencyPercent", 80))))).path("equipmentId").asText();
        call(get("/equipments/" + press), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.efficiencyPercent").value(80));
        String red = item("RED-" + tag);
        String blue = item("BLUE-" + tag);
        call(post("/equipments/" + press + "/changeovers"), json(Map.of("fromItemId", red, "toItemId", blue, "minutes", 30)))
            .andExpect(status().isOk());
        String redOrder = order("Red " + tag, red, 8, at(5, 6), at(5, 7), press);
        call(post("/work-orders/" + redOrder + "/approve"), null).andExpect(status().isOk());

        // 16 at 8 an hour is 2 h; the 30 min changeover after the red order is not scaled: 2.5 h from Monday 8.
        String blueOrder = order("Blue " + tag, blue, 16, at(5, 8), at(5, 9), press);
        call(get("/work-orders/" + blueOrder + "/plan-suggestion").param("from", at(5, 8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capacityPerHour").value(10))
            .andExpect(jsonPath("$.data.efficiencyPercent").value(80))
            .andExpect(jsonPath("$.data.productionHours").value(2))
            .andExpect(jsonPath("$.data.changeoverHours").value(0.5))
            .andExpect(jsonPath("$.data.neededHours").value(2.5))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-08-05T10:30:00+09:00"));

        // Readiness reckons the same over the order's own hour and says the efficiency.
        JsonNode check = null;
        for (JsonNode one : data(call(get("/work-orders/" + blueOrder + "/readiness"), null)).path("checks")) {
            if ("equipment".equals(one.path("code").asText())) {
                check = one;
            }
        }
        assertEquals("warn", check.path("status").asText());
        assertEquals("Equipment EFF-" + tag + " needs 2.5 h at 80% efficiency (with 0.5 h changeover) for 16 but has only 1 h"
            + " available in the planned window.", check.path("message").asText());

        // The load board counts the order at 8 an hour too.
        JsonNode row = null;
        for (JsonNode one : data(call(get("/equipment-load").param("projectId", DEMO_PROJECT).param("from", MONDAY)
            .param("to", NEXT_MONDAY), null)).path("equipment")) {
            if (press.equals(one.path("equipmentId").asText())) {
                row = one;
            }
        }
        assertEquals(80.0, row.path("efficiencyPercent").asDouble());
        JsonNode blueLine = null;
        for (JsonNode one : row.path("orders")) {
            if (blueOrder.equals(one.path("workOrderId").asText())) {
                blueLine = one;
            }
        }
        assertEquals(2.5, blueLine.path("neededHours").asDouble());

        // The planned side of the running comparison: 16 at 8 an hour is 120 minutes.
        call(get("/work-orders/" + blueOrder + "/running-comparison"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedMinutes").value(120))
            .andExpect(jsonPath("$.data.capacityPerHour").value(10))
            .andExpect(jsonPath("$.data.efficiencyPercent").value(80));

        call(put("/equipments/" + press), json(Map.of("details", Map.of("capacityPerHour", 10, "efficiencyPercent", 0))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("efficiencyPercent must be above 0 and at most 100.")));
        call(put("/equipments/" + press), json(Map.of("details", Map.of("capacityPerHour", 10, "efficiencyPercent", 100.5))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("efficiencyPercent must be above 0 and at most 100.")));
        call(put("/equipments/" + press), json(Map.of("details", Map.of("capacityPerHour", 10, "efficiencyPercent", 12.345))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("efficiencyPercent takes at most 2 decimals.")));
        // Without an efficiency the press is back at its rated 10 an hour.
        call(put("/equipments/" + press), json(Map.of("details", Map.of("capacityPerHour", 10)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.efficiencyPercent").value(nullValue()));
        call(get("/work-orders/" + blueOrder + "/plan-suggestion").param("from", at(5, 8)), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productionHours").value(1.6));

        call(post("/work-orders/" + redOrder + "/cancel"), "{}").andExpect(status().isOk());
    }

    private static String at(int day, int hour) {
        return String.format("2041-08-%02dT%02d:00:00+09:00", day, hour);
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
