package org.myweb.flowmat;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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

/** Plan suggestions for several work orders at once (docs/domain/equipment-schedule.md P8-P12) against real Postgres. */
@AutoConfigureMockMvc
class WorkOrderBatchPlanIntegrationTest extends IntegrationTestSupport {

    // 2030-01-07 is a Monday; the planning zone defaults to Asia/Seoul (+09:00).
    private static final String MONDAY = "2030-01-07T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void ordersPlannedTogetherQueueOnTheirEquipmentInTheOrderGiven() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String oven = id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "BATCH-" + tag,
            "equipmentName", "batch oven", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))), "equipmentId");
        call(put("/equipments/" + oven + "/calendar"), json(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
            "workDays", List.of(1, 2, 3, 4, 5)))).andExpect(status().isOk());
        String red = item("BRED-" + tag);
        String blue = item("BBLUE-" + tag);
        Map<String, Object> rule = new HashMap<>(Map.of("fromItemId", red, "toItemId", blue, "minutes", 60));
        call(post("/equipments/" + oven + "/changeovers"), json(rule)).andExpect(status().isOk());

        // Red needs 10 h, blue 5 h (1 h more after red); the loose order has no equipment.
        String first = workOrder("Red " + tag, red, 100, "2030-01-07T09:00:00+09:00", "2030-01-08T11:00:00+09:00");
        String second = workOrder("Blue " + tag, blue, 50, null, null);
        String loose = workOrder("Loose " + tag, null, 10, null, null);
        assign(first, oven);
        assign(second, oven);
        String firstNumber = data(call(get("/work-orders/" + first))).path("workOrderNumber").asText();
        String secondNumber = data(call(get("/work-orders/" + second))).path("workOrderNumber").asText();

        // In the order given: red Monday 09:00 to Tuesday 11:00, then blue after it with the changeover; the loose order says
        // why it has no dates and does not stop the others.
        batch(List.of(first, loose, second), MONDAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.from").value(MONDAY))
            .andExpect(jsonPath("$.data.lines.length()").value(3))
            .andExpect(jsonPath("$.data.lines[0].workOrderNumber").value(firstNumber))
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value("2030-01-07T09:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedEndAt").value("2030-01-08T11:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[0].problem").isEmpty())
            .andExpect(jsonPath("$.data.lines[1].suggestion").isEmpty())
            .andExpect(jsonPath("$.data.lines[1].problem").value("Assign equipment first; the dates follow its calendar."))
            .andExpect(jsonPath("$.data.lines[2].suggestion.plannedStartAt").value("2030-01-08T11:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[2].suggestion.plannedEndAt").value("2030-01-08T17:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[2].suggestion.changeoverHours").value(1))
            .andExpect(jsonPath("$.data.lines[2].suggestion.neededHours").value(6))
            .andExpect(jsonPath("$.data.lines[2].suggestion.changeoverFrom").value(firstNumber))
            .andExpect(jsonPath("$.data.lines[2].suggestion.movedPast[0]").value(firstNumber));
        // The other way round blue goes first; blue to red has no changeover rule.
        batch(List.of(second, first), MONDAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value("2030-01-07T09:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedEndAt").value("2030-01-07T14:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].suggestion.plannedStartAt").value("2030-01-07T14:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].suggestion.plannedEndAt").value("2030-01-08T16:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].suggestion.changeoverHours").value(0))
            .andExpect(jsonPath("$.data.lines[1].suggestion.movedPast[0]").value(secondNumber));

        // Once red is approved it is planned work for an order planned without it, but not for a batch that plans it again.
        call(post("/work-orders/" + first + "/approve")).andExpect(status().isOk());
        batch(List.of(second), MONDAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value("2030-01-08T11:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[0].suggestion.movedPast[0]").value(firstNumber));
        batch(List.of(second, first), MONDAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value("2030-01-07T09:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[0].suggestion.movedPast.length()").value(0))
            .andExpect(jsonPath("$.data.lines[1].workOrderStatus").value("approved"));
        // Without from, each order starts as its single suggestion would; the response says so with a null from.
        batch(List.of(second), null).andExpect(status().isOk()).andExpect(jsonPath("$.data.from").isEmpty());
        // Nothing was saved.
        call(get("/work-orders/" + second)).andExpect(jsonPath("$.data.plannedStartAt").isEmpty());

        batch(List.of(second, second), MONDAY).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Name each work order once."));
        batch(List.of(), MONDAY).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose 1 to 50 work orders."));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("wo-" + i);
        }
        batch(tooMany, MONDAY).andExpect(status().isBadRequest());
        batch(List.of(second, "no-such-order"), MONDAY).andExpect(status().isNotFound());
        batch(List.of(second), "monday").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("with an offset")));
        call(post("/work-orders/plan-suggestions"), json(Map.of("workOrderIds", List.of(second)))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("projectId is required."));
        call(post("/work-orders/plan-suggestions"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderIds\":{\"a\":1}}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("workOrderIds as a list")));
        mockMvc.perform(post("/work-orders/plan-suggestions").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("projectId", DEMO_PROJECT, "workOrderIds", List.of(second))))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());

        call(post("/work-orders/" + first + "/cancel"), "{}").andExpect(status().isOk());
    }

    @Test
    void theSuggestedOrderHasTheFewestChangeoversAfterTheOrderBefore() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String press = id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "SEQ-" + tag,
            "equipmentName", "sequence press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))), "equipmentId");
        String a = item("SA-" + tag);
        String b = item("SB-" + tag);
        String c = item("SC-" + tag);
        // A to B to C is cheap (10 each); every other change takes an hour.
        rule(press, a, b, 10);
        rule(press, b, c, 10);
        rule(press, a, c, 60);
        rule(press, b, a, 60);
        rule(press, c, a, 60);
        rule(press, c, b, 60);
        String orderA = workOrder("A " + tag, a, 10, null, null);
        String orderB = workOrder("B " + tag, b, 10, null, null);
        String orderC = workOrder("C " + tag, c, 10, null, null);
        String loose = workOrder("Loose " + tag, a, 10, null, null);
        assign(orderA, press);
        assign(orderB, press);
        assign(orderC, press);

        // Ticked C, loose, B, A (120 min of changeovers): A, B, C takes 20, and the order without equipment stays last.
        order(List.of(orderC, loose, orderB, orderA), "2030-01-07T10:00:00+09:00").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderIds").value(contains(orderA, orderB, orderC, loose)))
            .andExpect(jsonPath("$.data.changeoverMinutes").value(20))
            .andExpect(jsonPath("$.data.givenChangeoverMinutes").value(120))
            .andExpect(jsonPath("$.data.groups[0].equipmentId").value(press))
            .andExpect(jsonPath("$.data.groups[0].after").isEmpty());

        // An approved C order runs before the start: beginning with C is free, so C, A, B (70) beats A, B, C (80).
        String before = workOrder("Before " + tag, c, 10, "2030-01-07T08:00:00+09:00", "2030-01-07T09:00:00+09:00");
        assign(before, press);
        String beforeNumber = data(call(post("/work-orders/" + before + "/approve"))).path("workOrderNumber").asText();
        order(List.of(orderA, orderB, orderC), "2030-01-07T10:00:00+09:00").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderIds").value(contains(orderC, orderA, orderB)))
            .andExpect(jsonPath("$.data.changeoverMinutes").value(70))
            .andExpect(jsonPath("$.data.givenChangeoverMinutes").value(80))
            .andExpect(jsonPath("$.data.groups[0].after").value(beforeNumber));
        // Before that order there is nothing to change over from.
        order(List.of(orderA, orderB, orderC), "2030-01-07T07:00:00+09:00").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderIds").value(contains(orderA, orderB, orderC)))
            .andExpect(jsonPath("$.data.groups[0].after").isEmpty());

        order(List.of(orderA, orderA), null).andExpect(status().isBadRequest());
        mockMvc.perform(post("/work-orders/plan-order").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("projectId", DEMO_PROJECT, "workOrderIds", List.of(orderA))))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());

        call(post("/work-orders/" + before + "/cancel"), "{}").andExpect(status().isOk());
    }

    private ResultActions order(List<String> ids, String from) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderIds", ids);
        body.put("from", from);
        return call(post("/work-orders/plan-order"), json(body));
    }

    private void rule(String equipmentId, String fromItemId, String toItemId, int minutes) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fromItemId", fromItemId, "toItemId", toItemId, "minutes", minutes));
        call(post("/equipments/" + equipmentId + "/changeovers"), json(body)).andExpect(status().isOk());
    }

    private ResultActions batch(List<String> ids, String from) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderIds", ids);
        body.put("from", from);
        return call(post("/work-orders/plan-suggestions"), json(body));
    }

    private void assign(String workOrderId, String equipmentId) throws Exception {
        call(put("/work-orders/" + workOrderId + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
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
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
