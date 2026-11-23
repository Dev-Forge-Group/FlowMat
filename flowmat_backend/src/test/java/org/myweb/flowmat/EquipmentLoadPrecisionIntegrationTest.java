package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class EquipmentLoadPrecisionIntegrationTest extends IntegrationTestSupport {

    private static final String START = "2030-01-07T00:00:00+09:00";
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest
    @CsvSource({
        "2030-01-07T00:00:00.500000+09:00,2030-01-07T00:00:00.100000+09:00,2030-01-07T00:00:00.300000+09:00,4.0",
        "2030-01-07T00:00:01.500000+09:00,2030-01-07T00:00:00.500000+09:00,2030-01-07T00:00:01+09:00,3.33",
        "2030-01-07T00:00:00.500000+09:00,2030-01-06T15:00:00.100000Z,2030-01-06T15:00:00.350000Z,5.0"
    })
    void apportionsShortWindowsWithoutDroppingFractionalSeconds(String end, String from, String to, double hours)
        throws Exception {
        Fixture fixture = order(100, START, end);
        String row = "$.data.equipment[?(@.equipmentId == '" + fixture.equipment() + "')]";

        load(from, to).andExpect(status().isOk())
            .andExpect(jsonPath(row + ".plannedHours").value(hasItem(hours)))
            .andExpect(jsonPath(row + ".orders[0].neededHours").value(hasItem(10.0)))
            .andExpect(jsonPath(row + ".orders[0].hoursInWindow").value(hasItem(hours)));
    }

    @Test
    void anOpenOrderWhoseOutputExceedsItsTargetHasZeroRemainingQuantity() throws Exception {
        Fixture fixture = order(10, START, "2030-01-07T01:00:00+09:00");
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "workOrderId", fixture.order(), "plannedOutputQty", 10)))
            .path("productionRunId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 15))
            .andExpect(status().isOk());
        JsonNode equipment = null;
        for (JsonNode candidate : data(load(START, "2030-01-08T00:00:00+09:00")).path("equipment")) {
            if (candidate.path("equipmentId").asText().equals(fixture.equipment())) equipment = candidate;
        }
        assertTrue(equipment != null, "The assigned equipment must be included in the load response.");
        assertZero(equipment.path("orders").get(0).path("remainingQuantity"));
        assertZero(equipment.path("orders").get(0).path("neededHours"));
        assertZero(equipment.path("plannedHours"));
        call(get("/work-orders/" + fixture.order())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderStatus").value("in_progress"))
            .andExpect(jsonPath("$.data.producedQuantity").value(15));
    }

    @Test
    void anOpenOrderWhoseOutputExceedsItsTargetCannotGetANegativePlanSuggestion() throws Exception {
        Fixture fixture = order(10, START, "2030-01-07T01:00:00+09:00");
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "workOrderId", fixture.order(), "plannedOutputQty", 10)))
            .path("productionRunId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 15))
            .andExpect(status().isOk());

        call(get("/work-orders/" + fixture.order() + "/plan-suggestion")
                .param("from", START))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("The target quantity has already been produced."));
    }

    private Fixture order(int quantity, String start, String end) throws Exception {
        String tag = UUID.randomUUID().toString();
        String equipment = data(call(post("/equipments"), Map.of("projectId", DEMO_PROJECT,
            "equipmentCode", "LOAD-" + tag, "equipmentName", "Precision " + tag,
            "equipmentType", "machine", "details", Map.of("capacityPerHour", 10))))
            .path("equipmentId").asText();
        String order = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "workOrderTitle", "Load precision " + tag,
            "targetQuantity", quantity, "plannedStartAt", start, "plannedEndAt", end)))
            .path("workOrderId").asText();
        call(put("/work-orders/" + order + "/equipment"), Map.of("equipmentId", equipment))
            .andExpect(status().isOk());
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        return new Fixture(equipment, order);
    }

    private ResultActions load(String from, String to) throws Exception {
        return call(get("/equipment-load").param("projectId", DEMO_PROJECT).param("from", from).param("to", to));
    }

    private static void assertZero(JsonNode quantity) {
        assertTrue(quantity.isNumber());
        assertEquals(0, quantity.decimalValue().signum());
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }

    private record Fixture(String equipment, String order) {}
}
