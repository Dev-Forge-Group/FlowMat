package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Changeover rules on equipment and the changeover time in work order readiness (docs/domain/equipment-changeover.md). */
@AutoConfigureMockMvc
class EquipmentChangeoverIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void rulesAreOnePerPairAndTheMostSpecificOneIsListedFirst() throws Exception {
        String tag = tag();
        String line = equipment("LINE-" + tag);
        String red = item("RED-" + tag);
        String white = item("WHITE-" + tag);

        call(post(changeovers(line)), json(rule(null, null, 30, "Rinse"))).andExpect(status().isOk());
        call(post(changeovers(line)), json(rule(red, white, 90, "Full clean")))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].fromItemCode").value("RED-" + tag))
            .andExpect(jsonPath("$.data[0].toItemCode").value("WHITE-" + tag))
            .andExpect(jsonPath("$.data[0].minutes").value(90))
            .andExpect(jsonPath("$.data[1].fromItemId").isEmpty())
            .andExpect(jsonPath("$.data[1].note").value("Rinse"));
        call(post(changeovers(line)), json(rule(red, white, 45, null)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already set")));
        call(post(changeovers(line)), json(rule(null, null, 10, null))).andExpect(status().isConflict());
        call(post(changeovers(line)), json(rule(white, red, 0, null))).andExpect(status().isBadRequest());
        call(post(changeovers(line)), json(rule(white, red, 10081, null))).andExpect(status().isBadRequest());
        call(post(changeovers(line)), json(rule("no-such-item", red, 10, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("fromItemId")));

        JsonNode list = data(call(get(changeovers(line))));
        String exact = list.get(0).path("changeoverId").asText();
        call(put(changeovers(line) + "/" + exact), json(Map.of("minutes", 120, "note", " ")))
            .andExpect(jsonPath("$.data[0].minutes").value(120))
            .andExpect(jsonPath("$.data[0].note").isEmpty());
        call(put(changeovers(line) + "/" + exact), json(Map.of("minutes", -5))).andExpect(status().isBadRequest());
        call(delete(changeovers(line) + "/" + exact)).andExpect(jsonPath("$.data.length()").value(1));
        call(delete(changeovers(line) + "/" + exact)).andExpect(status().isNotFound());
        call(post(changeovers(line)), json(rule(red, white, 60, null))).andExpect(status().isOk());

        callAs("unrelated-user", get(changeovers(line))).andExpect(status().isForbidden());
        callAs("unrelated-user", post(changeovers(line)).contentType(MediaType.APPLICATION_JSON)
            .content(json(rule(white, red, 10, null)))).andExpect(status().isForbidden());
    }

    @Test
    void readinessCountsTheChangeoverFromThePreviousOrderOnTheEquipment() throws Exception {
        String tag = tag();
        String oven = equipment("OVEN-" + tag);
        call(put("/equipments/" + oven + "/calendar"), json(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
            "workDays", List.of(1, 2, 3, 4, 5)))).andExpect(status().isOk());
        String rye = item("RYE-" + tag);
        String wheat = item("WHEAT-" + tag);
        String spelt = item("SPELT-" + tag);
        call(post(changeovers(oven)), json(rule(rye, wheat, 120, null))).andExpect(status().isOk());
        call(post(changeovers(oven)), json(rule(null, null, 30, null))).andExpect(status().isOk());

        // Rye on Monday, then wheat Tuesday-Wednesday (100 at 10 an hour: 10 h in two 8 h shifts).
        String first = order("Rye " + tag, rye, "2030-01-07T09:00:00+09:00", "2030-01-07T17:00:00+09:00", oven);
        String second = order("Wheat " + tag, wheat, "2030-01-08T09:00:00+09:00", "2030-01-09T17:00:00+09:00", oven);
        readiness(second).andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')]").isEmpty());

        String firstNumber = data(call(post("/work-orders/" + first + "/approve"))).path("workOrderNumber").asText();
        readiness(second)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')].message").value(hasItem(containsString(firstNumber))))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')].message")
                .value(hasItem(containsString("120 min changeover from RYE-" + tag + " to WHEAT-" + tag))))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message")
                .value(hasItem(containsString("needs 12 h (with 2 h changeover) of the 16 h available"))));

        // Spelt after rye has no own rule, so the any-to-any 30 minutes apply.
        String third = order("Spelt " + tag, spelt, "2030-01-10T09:00:00+09:00", "2030-01-11T17:00:00+09:00", oven);
        readiness(third)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')].message")
                .value(hasItem(containsString("30 min changeover from RYE-" + tag + " to SPELT-" + tag))))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message")
                .value(hasItem(containsString("needs 10.5 h (with 0.5 h changeover) of the 16 h available"))));

        // Rye again after rye needs no changeover.
        String again = order("Rye again " + tag, rye, "2030-01-08T09:00:00+09:00", "2030-01-09T17:00:00+09:00", oven);
        readiness(again).andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')]").isEmpty());

        // Without rules the second order just needs its production time.
        for (JsonNode one : data(call(get(changeovers(oven))))) {
            call(delete(changeovers(oven) + "/" + one.path("changeoverId").asText())).andExpect(status().isOk());
        }
        readiness(second)
            .andExpect(jsonPath("$.data.checks[?(@.code == 'changeover')]").isEmpty())
            .andExpect(jsonPath("$.data.checks[?(@.code == 'equipment')].message")
                .value(hasItem(containsString("needs 10 h of the 16 h available"))));
    }

    private static String changeovers(String equipmentId) {
        return "/equipments/" + equipmentId + "/changeovers";
    }

    private static Map<String, Object> rule(String from, String to, int minutes, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromItemId", from);
        body.put("toItemId", to);
        body.put("minutes", minutes);
        body.put("note", note);
        return body;
    }

    private String equipment(String code) throws Exception {
        return id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))), "equipmentId");
    }

    private String item(String code) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "product", "unitId", "unit_ea"))), "itemId");
    }

    private String order(String title, String itemId, String start, String end, String equipmentId) throws Exception {
        String orderId = id(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "workflowId", DEMO_WORKFLOW, "targetItemId", itemId, "targetQuantity", 100, "plannedStartAt", start,
            "plannedEndAt", end))), "workOrderId");
        call(put("/work-orders/" + orderId + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        return orderId;
    }

    private ResultActions readiness(String workOrderId) throws Exception {
        return call(get("/work-orders/" + workOrderId + "/readiness")).andExpect(status().isOk());
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
