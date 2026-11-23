package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/** The equipment load board (docs/domain/equipment-load.md) against real Postgres. */
@AutoConfigureMockMvc
class EquipmentLoadIntegrationTest extends IntegrationTestSupport {

    // 2030-01-07 is a Monday; the planning zone defaults to Asia/Seoul (+09:00).
    private static final String MONDAY = "2030-01-07T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2030-01-14T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void theBoardSetsWhatOrdersNeedAgainstWhatTheCalendarLeaves() throws Exception {
        String tag = tag();
        String line = equipment("LOAD-A-" + tag, 10);
        String spare = equipment("LOAD-B-" + tag, 5);
        call(put("/equipments/" + line + "/calendar"), json(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
            "workDays", List.of(1, 2, 3, 4, 5)))).andExpect(status().isOk());
        String red = item("LRED-" + tag);
        String blue = item("LBLUE-" + tag);
        call(post("/equipments/" + line + "/changeovers"), json(Map.of("fromItemId", red, "toItemId", blue, "minutes", 60)))
            .andExpect(status().isOk());

        // 100 red Monday-Tuesday: 10 h. 50 as a draft on Wednesday: 5 h. 300 blue Thursday to the next Tuesday: 30 h plus
        // 1 h changeover from red, of which Thursday and Friday (16 of its 32 h) fall in the week.
        String first = order("Red " + tag, red, 100, "2030-01-07T09:00:00+09:00", "2030-01-08T17:00:00+09:00", line, true);
        order("Draft " + tag, null, 50, "2030-01-09T09:00:00+09:00", "2030-01-09T17:00:00+09:00", line, false);
        String across = order("Blue " + tag, blue, 300, "2030-01-10T09:00:00+09:00", "2030-01-15T17:00:00+09:00", line, true);
        order("No window " + tag, null, 10, null, null, line, true);
        order("No quantity " + tag, null, null, "2030-01-09T09:00:00+09:00", "2030-01-09T12:00:00+09:00", line, true);
        order("Next month " + tag, null, 10, "2030-02-04T09:00:00+09:00", "2030-02-04T17:00:00+09:00", line, true);

        String row = "$.data.equipment[?(@.equipmentId == '" + line + "')]";
        load(MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath(row + ".availableHours").value(hasItem(40.0)))
            .andExpect(jsonPath(row + ".plannedHours").value(hasItem(25.5)))
            .andExpect(jsonPath(row + ".draftHours").value(hasItem(5.0)))
            .andExpect(jsonPath(row + ".loadPercent").value(hasItem(63.8)))
            .andExpect(jsonPath(row + ".overloaded").value(hasItem(false)))
            .andExpect(jsonPath(row + ".unplannedOrders").value(hasItem(1)))
            .andExpect(jsonPath(row + ".unmeasuredOrders").value(hasItem(1)))
            .andExpect(jsonPath(row + ".orders.length()").value(hasItem(4)))
            .andExpect(jsonPath(row + ".orders[?(@.workOrderId == '" + first + "')].hoursInWindow").value(hasItem(10.0)))
            .andExpect(jsonPath(row + ".orders[?(@.workOrderId == '" + across + "')].changeoverMinutes").value(hasItem(60)))
            .andExpect(jsonPath(row + ".orders[?(@.workOrderId == '" + across + "')].neededHours").value(hasItem(31.0)))
            .andExpect(jsonPath(row + ".orders[?(@.workOrderId == '" + across + "')].hoursInWindow").value(hasItem(15.5)))
            .andExpect(jsonPath("$.data.equipment[?(@.equipmentId == '" + spare + "')].availableHours").value(hasItem(168.0)))
            .andExpect(jsonPath("$.data.equipment[?(@.equipmentId == '" + spare + "')].loadPercent").value(hasItem(0.0)));

        // Another 200 red over the whole week pushes the line past its 40 h.
        order("More red " + tag, red, 200, "2030-01-07T09:00:00+09:00", "2030-01-11T17:00:00+09:00", line, true);
        load(MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath(row + ".plannedHours").value(hasItem(45.5)))
            .andExpect(jsonPath(row + ".loadPercent").value(hasItem(113.8)))
            .andExpect(jsonPath(row + ".overloaded").value(hasItem(true)));

        load(NEXT_MONDAY, MONDAY).andExpect(status().isBadRequest());
        load(MONDAY, "2030-05-01T00:00:00+09:00")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("92 days")));
        load("monday", NEXT_MONDAY).andExpect(status().isBadRequest());
        call(get("/equipment-load").param("from", MONDAY).param("to", NEXT_MONDAY)).andExpect(status().isBadRequest());
        callAs("unrelated-user", get("/equipment-load").param("projectId", DEMO_PROJECT).param("from", MONDAY).param("to", NEXT_MONDAY))
            .andExpect(status().isForbidden());
    }

    @Test
    void theWeeksChangeoversAreAddedUpAndAnOrderWithFewerIsSuggested() throws Exception {
        String tag = tag();
        String press = equipment("SEQ-" + tag, 10);
        String a = item("SEQA-" + tag);
        String b = item("SEQB-" + tag);
        call(post("/equipments/" + press + "/changeovers"), json(Map.of("fromItemId", a, "toItemId", b, "minutes", 30)))
            .andExpect(status().isOk());
        call(post("/equipments/" + press + "/changeovers"), json(Map.of("fromItemId", b, "toItemId", a, "minutes", 45)))
            .andExpect(status().isOk());
        // A on Monday, B on Tuesday and A again (a draft) on Wednesday: 30 + 45 min as planned, 30 with both A first.
        String first = order("A1 " + tag, a, 10, "2030-01-07T09:00:00+09:00", "2030-01-07T17:00:00+09:00", press, true);
        String middle = order("B " + tag, b, 10, "2030-01-08T09:00:00+09:00", "2030-01-08T17:00:00+09:00", press, true);
        String last = order("A2 " + tag, a, 10, "2030-01-09T09:00:00+09:00", "2030-01-09T17:00:00+09:00", press, false);

        String plan = "$.data.equipment[?(@.equipmentId == '" + press + "')].changeovers";
        load(MONDAY, NEXT_MONDAY)
            .andExpect(jsonPath(plan + ".plannedMinutes").value(hasItem(75)))
            .andExpect(jsonPath(plan + ".suggestedMinutes").value(hasItem(30)))
            .andExpect(jsonPath(plan + ".suggestedOrder").value(hasItem(List.of(number(first), number(last), number(middle)))));

        // The next week has no order with an item on the press: nothing to add up.
        load(NEXT_MONDAY, "2030-01-21T00:00:00+09:00").andExpect(jsonPath(plan + ".plannedMinutes").isEmpty());
    }

    private String number(String workOrderId) throws Exception {
        return data(call(get("/work-orders/" + workOrderId))).path("workOrderNumber").asText();
    }

    private ResultActions load(String from, String to) throws Exception {
        return call(get("/equipment-load").param("projectId", DEMO_PROJECT).param("from", from).param("to", to));
    }

    private String equipment(String code, int capacityPerHour) throws Exception {
        return id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine",
            "details", Map.of("capacityPerHour", capacityPerHour)))), "equipmentId");
    }

    private String item(String code) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "product", "unitId", "unit_ea"))), "itemId");
    }

    private String order(String title, String itemId, Integer quantity, String start, String end, String equipmentId,
                         boolean approve) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", itemId);
        body.put("targetQuantity", quantity);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        String orderId = id(call(post("/work-orders"), json(body)), "workOrderId");
        call(put("/work-orders/" + orderId + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        if (approve) {
            call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        }
        return orderId;
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
