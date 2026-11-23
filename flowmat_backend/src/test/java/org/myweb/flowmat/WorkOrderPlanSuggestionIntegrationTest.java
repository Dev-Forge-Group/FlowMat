package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
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

/** Suggested planned start and end of a work order from its equipment's calendar (docs/domain/equipment-schedule.md). */
@AutoConfigureMockMvc
class WorkOrderPlanSuggestionIntegrationTest extends IntegrationTestSupport {

    // 2030-01-07 is a Monday; the planning zone defaults to Asia/Seoul (+09:00).
    private static final String MONDAY = "2030-01-07T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void theSuggestionFillsTheShiftsSkipsDowntimeAndMovesPastPlannedOrders() throws Exception {
        String tag = tag();
        String oven = equipment("OVEN-" + tag, 10);
        call(put("/equipments/" + oven + "/calendar"), json(calendar("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk());
        // 100 at 10 an hour needs 10 h: Monday's 8 h shift and two hours on Tuesday.
        String order = workOrder("Bake " + tag, null, 100, "2030-01-07T09:00:00+09:00", "2030-01-07T10:00:00+09:00");
        suggestion(order, MONDAY).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Assign equipment first; the dates follow its calendar."));
        assign(order, oven);

        suggestion(order, MONDAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T09:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-08T11:00:00+09:00"))
            .andExpect(jsonPath("$.data.productionHours").value(10))
            .andExpect(jsonPath("$.data.changeoverHours").value(0))
            .andExpect(jsonPath("$.data.neededHours").value(10))
            .andExpect(jsonPath("$.data.remainingQuantity").value(100))
            .andExpect(jsonPath("$.data.changeoverFrom").isEmpty())
            .andExpect(jsonPath("$.data.movedPast.length()").value(0));
        // Without from, the planned start (still ahead) is where the search begins.
        suggestion(order, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T09:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-08T11:00:00+09:00"));
        // From Monday 16:00: one hour on Monday, eight on Tuesday, one on Wednesday.
        suggestion(order, "2030-01-07T16:00:00+09:00")
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T16:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-09T10:00:00+09:00"));

        // An hour down on Tuesday morning pushes the end an hour later.
        call(post("/equipments/" + oven + "/downtimes"), json(Map.of("downtimeType", "maintenance",
            "startsAt", "2030-01-08T09:00:00+09:00", "endsAt", "2030-01-08T10:00:00+09:00"))).andExpect(status().isOk());
        suggestion(order, MONDAY).andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-08T12:00:00+09:00"));

        // A draft on the same oven is not planned work yet; an approved one is moved past.
        String draft = workOrder("Draft " + tag, null, 10, "2030-01-07T09:00:00+09:00", "2030-01-07T17:00:00+09:00");
        assign(draft, oven);
        suggestion(order, MONDAY).andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T09:00:00+09:00"));
        String other = workOrder("Other " + tag, null, 20, "2030-01-07T13:00:00+09:00", "2030-01-07T15:00:00+09:00");
        assign(other, oven);
        String otherNumber = data(call(post("/work-orders/" + other + "/approve"))).path("workOrderNumber").asText();
        // From 15:00: two hours on Monday, seven on Tuesday (one down), one on Wednesday.
        suggestion(order, MONDAY)
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T15:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-09T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast[0]").value(otherNumber));

        suggestion(order, "monday").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("with an offset")));
        callAs("unrelated-user", get("/work-orders/" + order + "/plan-suggestion")).andExpect(status().isForbidden());

        // More than 366 days of shifts would be needed.
        String huge = workOrder("Huge " + tag, null, 100_000, "2030-01-07T09:00:00+09:00", "2030-01-07T10:00:00+09:00");
        assign(huge, oven);
        suggestion(huge, MONDAY).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("does not have 10000 h available in the 366 days")));

        call(post("/work-orders/" + other + "/cancel"), "{}").andExpect(status().isOk());
    }

    @Test
    void theChangeoverFromTheOrderBeforeIsAddedAndMissingFactsAreExplained() throws Exception {
        String tag = tag();
        String press = equipment("PRESS-" + tag, 10);
        String red = item("RED-" + tag);
        String blue = item("BLUE-" + tag);
        call(post("/equipments/" + press + "/changeovers"), json(rule(red, blue, 90))).andExpect(status().isOk());
        // Around the clock: an approved red order runs 08:00-10:00, so a blue order after it needs 1.5 h more.
        String redOrder = workOrder("Red " + tag, red, 20, "2030-01-07T08:00:00+09:00", "2030-01-07T10:00:00+09:00");
        assign(redOrder, press);
        String redNumber = data(call(post("/work-orders/" + redOrder + "/approve"))).path("workOrderNumber").asText();
        String blueOrder = workOrder("Blue " + tag, blue, 100, "2030-01-07T10:00:00+09:00", "2030-01-07T12:00:00+09:00");
        assign(blueOrder, press);

        suggestion(blueOrder, "2030-01-07T10:00:00+09:00").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2030-01-07T21:30:00+09:00"))
            .andExpect(jsonPath("$.data.changeoverHours").value(1.5))
            .andExpect(jsonPath("$.data.neededHours").value(11.5))
            .andExpect(jsonPath("$.data.changeoverFrom").value(redNumber));
        // Asked from 09:00 the red order is in the way; after it the changeover applies the same way.
        suggestion(blueOrder, "2030-01-07T09:00:00+09:00")
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.movedPast[0]").value(redNumber))
            .andExpect(jsonPath("$.data.changeoverFrom").value(redNumber));
        // From 07:00 nothing comes before, but 07:00-17:00 would cross the red order; after it the changeover counts.
        suggestion(blueOrder, "2030-01-07T07:00:00+09:00")
            .andExpect(jsonPath("$.data.plannedStartAt").value("2030-01-07T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.changeoverHours").value(1.5));

        String slow = id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "SLOW-" + tag,
            "equipmentName", "slow", "equipmentType", "machine"))), "equipmentId");
        assign(blueOrder, slow);
        suggestion(blueOrder, MONDAY).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Equipment SLOW-" + tag + " has no capacity per hour, so how long the order takes is unknown."));
        String noQuantity = workOrder("No quantity " + tag, blue, null, null, null);
        assign(noQuantity, press);
        suggestion(noQuantity, MONDAY).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Set a target quantity first."));
        call(post("/work-orders/" + noQuantity + "/cancel"), "{}").andExpect(status().isOk());
        suggestion(noQuantity, MONDAY).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is cancelled; it is not planned any more.")));
        call(post("/work-orders/" + redOrder + "/cancel"), "{}").andExpect(status().isOk());
    }

    private ResultActions suggestion(String workOrderId, String from) throws Exception {
        MockHttpServletRequestBuilder request = get("/work-orders/" + workOrderId + "/plan-suggestion");
        return call(from == null ? request : request.param("from", from));
    }

    private void assign(String workOrderId, String equipmentId) throws Exception {
        call(put("/work-orders/" + workOrderId + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
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

    private String workOrder(String title, String itemId, Integer quantity, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", itemId);
        body.put("targetQuantity", quantity);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        return id(call(post("/work-orders"), json(body)), "workOrderId");
    }

    private static Map<String, Object> rule(String from, String to, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromItemId", from);
        body.put("toItemId", to);
        body.put("minutes", minutes);
        return body;
    }

    private static Map<String, Object> calendar(String start, String end, Integer... days) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(days));
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
