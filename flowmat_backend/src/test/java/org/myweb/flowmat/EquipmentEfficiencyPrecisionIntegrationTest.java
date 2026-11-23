package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class EquipmentEfficiencyPrecisionIntegrationTest extends IntegrationTestSupport {
    private static final String START = "2041-08-05T08:00:00+09:00";
    private static final String END = "2041-08-05T09:00:00+09:00";
    @Autowired private MockMvc mvc;
    @Autowired private JwtProvider jwt;
    @Autowired private ObjectMapper json;

    @ParameterizedTest
    @ValueSource(strings = {"plan", "load", "readiness", "comparison"})
    void allPlanningConsumersUseTheExactPositiveRate(String consumer) throws Exception {
        Fixture fixture = fixture("50");
        switch (consumer) {
            case "plan" -> {
                JsonNode plan = call(get("/work-orders/" + fixture.order + "/plan-suggestion").param("from", START), null);
                assertThat(plan.path("productionHours").decimalValue()).isEqualByComparingTo("2");
                assertThat(plan.path("neededHours").decimalValue()).isEqualByComparingTo("2");
            }
            case "load" -> {
                JsonNode load = call(get("/equipment-load").param("projectId", fixture.project).param("from", START).param("to", END), null);
                JsonNode equipment = find(load.path("equipment"), "equipmentId", fixture.equipment);
                JsonNode order = find(equipment.path("orders"), "workOrderId", fixture.order);
                assertThat(order.path("neededHours").decimalValue()).isEqualByComparingTo("2");
            }
            case "readiness" -> {
                JsonNode check = find(call(get("/work-orders/" + fixture.order + "/readiness"), null).path("checks"), "code", "equipment");
                assertThat(check.path("status").asText()).isEqualTo("warn");
                assertThat(check.path("message").asText()).contains("needs 2 h at 50% efficiency");
            }
            case "comparison" -> assertThat(call(get("/work-orders/" + fixture.order + "/running-comparison"), null)
                .path("plannedMinutes").asInt()).isEqualTo(120);
            default -> throw new AssertionError(consumer);
        }
    }

    @Test
    void theSmallestLegalEfficiencyDoesNotBecomeADivisionByZero() throws Exception {
        Fixture fixture = fixture("0.01");
        JsonNode comparison = call(get("/work-orders/" + fixture.order + "/running-comparison"), null);
        assertThat(comparison.path("plannedMinutes").asInt()).isEqualTo(600000);
    }

    private Fixture fixture(String efficiency) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String projectId = call(post("/projects"), Map.of("projectName", "Precision " + suffix, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String workflowId = call(post("/workflows"), Map.of("projectId", projectId, "workflowName", "Precision flow"))
            .path("workflowId").asText();
        JsonNode equipment = call(post("/equipments"), Map.of("projectId", projectId, "equipmentCode", "EP-" + suffix,
            "equipmentName", "Precision press", "equipmentType", "machine", "details",
            Map.of("capacityPerHour", new BigDecimal("0.0001"), "efficiencyPercent", new BigDecimal(efficiency))));
        String equipmentId = equipment.path("equipmentId").asText();
        JsonNode order = call(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflowId,
            "workOrderTitle", "Precision " + suffix, "targetQuantity", new BigDecimal("0.0001"),
            "plannedStartAt", START, "plannedEndAt", END));
        String orderId = order.path("workOrderId").asText();
        assertThat(order.path("bomId").isNull()).isTrue();
        call(put("/work-orders/" + orderId + "/equipment"), Map.of("equipmentId", equipmentId));
        return new Fixture(projectId, equipmentId, orderId);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var result = mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static JsonNode find(JsonNode array, String field, String id) {
        for (JsonNode row : array) if (id.equals(row.path(field).asText())) return row;
        throw new AssertionError("Missing " + field + ": " + id);
    }

    private record Fixture(String project, String equipment, String order) { }
}
