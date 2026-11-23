package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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


/**
 * The sequence snapshot (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md decision 2) against real Postgres:
 * the load board's work orders for one equipment and week, their times through the calendar, and what the plan and the
 * current changeover rule cost.
 */
@AutoConfigureMockMvc
class SequenceSnapshotIntegrationTest extends IntegrationTestSupport {

    // 2030-01-07 is a Monday; the planning zone defaults to Asia/Seoul (+09:00).
    private static final String MONDAY = "2030-01-07T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void theSnapshotHoldsTheWeeksOrdersAndWhatThePlanAndTheCurrentRuleCost() throws Exception {
        String tag = tag();
        String press = equipment("SNAP-" + tag, 10);
        call(put("/equipments/" + press + "/calendar"), json(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
            "workDays", List.of(1, 2, 3, 4, 5)))).andExpect(status().isOk());
        String a = item("SNAPA-" + tag);
        String b = item("SNAPB-" + tag);
        call(post("/equipments/" + press + "/changeovers"), json(Map.of("fromItemId", a, "toItemId", b, "minutes", 30)))
            .andExpect(status().isOk());
        call(post("/equipments/" + press + "/changeovers"), json(Map.of("fromItemId", b, "toItemId", a, "minutes", 45)))
            .andExpect(status().isOk());
        // A on Monday, B on Tuesday, A again (a draft) on Wednesday, 10 each at 10 an hour; and one without a quantity.
        String first = order("A1 " + tag, a, 10, "2030-01-07T09:00:00+09:00", "2030-01-07T17:00:00+09:00", press, true);
        String middle = order("B " + tag, b, 10, "2030-01-08T09:00:00+09:00", "2030-01-08T17:00:00+09:00", press, true);
        String last = order("A2 " + tag, a, 10, "2030-01-09T09:00:00+09:00", "2030-01-09T17:00:00+09:00", press, false);
        String blank = order("No quantity " + tag, a, null, "2030-01-10T09:00:00+09:00", "2030-01-10T17:00:00+09:00", press, false);

        JsonNode snapshot = data(snapshot(press, MONDAY));
        assertThat(snapshot.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.path("jobs").findValuesAsText("workOrderNumber"))
            .containsExactly(number(first), number(middle), number(last));
        assertThat(snapshot.path("jobs").get(0).path("productionSeconds").asLong()).isEqualTo(3600);
        assertThat(snapshot.path("jobs").get(2).path("status").asText()).isEqualTo("draft");
        assertThat(snapshot.path("leftOut").get(0).path("workOrderNumber").asText()).isEqualTo(number(blank));
        assertThat(snapshot.path("leftOut").get(0).path("reason").asText()).isEqualTo("no target quantity");
        assertThat(snapshot.path("changeovers").path(a).path(b).asInt()).isEqualTo(30);
        assertThat(snapshot.path("changeovers").path(b).path(a).asInt()).isEqualTo(45);
        assertThat(java.time.OffsetDateTime.parse(snapshot.path("available").get(0).path("start").asText()).toInstant())
            .isEqualTo(java.time.OffsetDateTime.parse("2030-01-07T09:00:00+09:00").toInstant());
        assertThat(snapshot.path("weights").path("lateMinute").path("urgent").decimalValue()).isEqualByComparingTo("4");

        // As planned: A 09:00-10:00, 30 min to B and B until 11:30, 45 min to A and A until 13:15, all before their ends.
        JsonNode plan = snapshot.path("plan");
        assertThat(plan.path("changeoverMinutes").asInt()).isEqualTo(75);
        assertThat(plan.path("lateMinutes").asLong()).isZero();
        assertThat(plan.path("objective").decimalValue()).isEqualByComparingTo("75");
        assertThat(java.time.OffsetDateTime.parse(plan.path("placements").get(2).path("end").asText()).toInstant())
            .isEqualTo(java.time.OffsetDateTime.parse("2030-01-07T13:15:00+09:00").toInstant());
        // The load board's suggestion: both A first.
        JsonNode rule = snapshot.path("currentRule");
        assertThat(toList(rule.path("order"))).containsExactly(number(first), number(last), number(middle));
        assertThat(rule.path("objective").decimalValue()).isEqualByComparingTo("30");

        snapshot(press, "next monday").andExpect(status().isBadRequest());
        call(get("/equipment-load/sequence-snapshot").param("projectId", DEMO_PROJECT).param("equipmentId", "missing")
            .param("from", MONDAY)).andExpect(status().isNotFound());
        callAs("snapshot-outsider-" + tag, get("/equipment-load/sequence-snapshot").param("projectId", DEMO_PROJECT)
            .param("equipmentId", press).param("from", MONDAY)).andExpect(status().isForbidden());
    }

    private static List<String> toList(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private ResultActions snapshot(String equipmentId, String from) throws Exception {
        return call(get("/equipment-load/sequence-snapshot").param("projectId", DEMO_PROJECT).param("equipmentId", equipmentId)
            .param("from", from));
    }

    private String number(String workOrderId) throws Exception {
        return data(call(get("/work-orders/" + workOrderId))).path("workOrderNumber").asText();
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
