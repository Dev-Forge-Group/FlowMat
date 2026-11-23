package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class StockAllocationPrecisionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest
    @CsvSource({"0.00005, 0.0001", "1.23454, 1.2345", "1.23455, 1.2346"})
    void aRoundedReservationCanBeConsumedWithoutLeavingReservedStock(String requested, BigDecimal stored) throws Exception {
        Fixture fixture = fixture();
        allocate(fixture, List.of(line(fixture.itemId(), requested))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations[0].quantity").value(stored.doubleValue()))
            .andExpect(jsonPath("$.data.plan[0].needed").value(stored.doubleValue()));
        stock(fixture, BigDecimal.TEN, stored);
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "workOrderId", fixture.orderId(), "plannedOutputQty", 1)))
            .path("productionRunId").asText();

        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", fixture.itemId(),
            "inventoryId", fixture.stockId(), "direction", "input", "plannedQty", stored, "actualQty", stored, "unit", "kg"))
            .andExpect(status().isOk());

        stock(fixture, BigDecimal.TEN.subtract(stored), BigDecimal.ZERO);
        call(get("/work-orders/" + fixture.orderId() + "/allocations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(stored.doubleValue()))
            .andExpect(jsonPath("$.data.allocations[0].status").value("closed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00001", "0.000049"})
    void aRequestBelowStoredPrecisionReturns400AndLeavesNoReservation(String quantity) throws Exception {
        Fixture fixture = fixture();

        allocate(fixture, List.of(line(fixture.itemId(), quantity))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("lines.quantity")));

        stock(fixture, BigDecimal.TEN, BigDecimal.ZERO);
        call(get("/work-orders/" + fixture.orderId() + "/allocations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations.length()").value(0));
    }

    @Test
    void smallRepeatedLinesAreAddedBeforeRounding() throws Exception {
        Fixture fixture = fixture();

        allocate(fixture, List.of(line(fixture.itemId(), "0.000025"), line(fixture.itemId(), "0.000025")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.allocations.length()").value(1))
            .andExpect(jsonPath("$.data.allocations[0].quantity").value(0.0001));

        stock(fixture, BigDecimal.TEN, new BigDecimal("0.0001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e-2147483647", "1e-1000000"})
    void extremeUnderflowIsRejectedWithoutExpandingItsDecimalPlaces(String quantity) throws Exception {
        Fixture fixture = fixture();
        allocate(fixture, List.of(line(fixture.itemId(), quantity))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("lines.quantity")));
        stock(fixture, BigDecimal.TEN, BigDecimal.ZERO);
        call(get("/work-orders/" + fixture.orderId() + "/allocations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations.length()").value(0));
        allocate(fixture, List.of(line(fixture.itemId(), "0.00005"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations[0].quantity").value(0.0001));
        stock(fixture, BigDecimal.TEN, new BigDecimal("0.0001"));
    }

    @ParameterizedTest
    @CsvSource({"0.1234, 0.0001, 0.0009", "0.96, 0.001, 0"})
    void bomDemandAfterAConvertedInputDoesNotLeaveAnUnrepresentableReservation(
        BigDecimal grams, BigDecimal spent, BigDecimal remaining
    ) throws Exception {
        Fixture fixture = fixture(new BigDecimal("0.001"));
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "workOrderId", fixture.orderId(), "plannedOutputQty", 1)))
            .path("productionRunId").asText();
        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", fixture.itemId(),
            "inventoryId", fixture.stockId(), "direction", "input", "plannedQty", grams, "actualQty", grams, "unit", "g"))
            .andExpect(status().isOk());

        ResultActions result = call(post("/work-orders/" + fixture.orderId() + "/allocations"), Map.of())
            .andExpect(status().isOk());

        if (remaining.signum() == 0) {
            result.andExpect(jsonPath("$.data.allocations.length()").value(0))
                .andExpect(jsonPath("$.data.plan.length()").value(0));
        } else {
            result.andExpect(jsonPath("$.data.allocations[0].quantity").value(remaining.doubleValue()))
                .andExpect(jsonPath("$.data.plan[0].needed").value(remaining.doubleValue()));
        }
        stock(fixture, BigDecimal.TEN.subtract(spent), remaining);
    }

    private Map<String, Object> line(String itemId, String quantity) {
        return Map.of("itemId", itemId, "quantity", new BigDecimal(quantity));
    }

    private ResultActions allocate(Fixture fixture, List<Map<String, Object>> lines) throws Exception {
        return call(post("/work-orders/" + fixture.orderId() + "/allocations"), Map.of("lines", lines));
    }

    private void stock(Fixture fixture, BigDecimal quantity, BigDecimal reserved) throws Exception {
        call(get("/inventories/" + fixture.stockId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(quantity.doubleValue()))
            .andExpect(jsonPath("$.data.reservedQuantity").value(reserved.doubleValue()));
    }

    private Fixture fixture() throws Exception {
        return fixture(null);
    }

    private Fixture fixture(BigDecimal materialRequirement) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "PRECISION-" + tag,
            "itemName", "Reservation precision " + tag, "unitId", "unit_kg"))).path("itemId").asText();
        String stockId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "location", "PRECISION-" + tag, "quantity", 10))).path("inventoryId").asText();
        Map<String, Object> order = new HashMap<>(Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Precision " + tag, "workflowId", DEMO_WORKFLOW));
        if (materialRequirement != null) {
            String product = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
                "itemCode", "PRECISION-PRODUCT-" + tag, "itemName", "Precision product " + tag, "unitId", "unit_ea")))
                .path("itemId").asText();
            String bom = data(call(post("/boms"), Map.of("projectId", DEMO_PROJECT, "targetItemId", product,
                "bomName", "Precision " + tag, "baseQuantity", 1, "baseUnit", "ea"))).path("bomId").asText();
            data(call(post("/boms/" + bom + "/lines"), Map.of("childItemId", itemId, "quantity", materialRequirement, "unit", "kg")));
            data(call(post("/boms/" + bom + "/submit")));
            data(call(post("/boms/" + bom + "/approve")));
            order.put("bomId", bom);
            order.put("targetQuantity", 1);
        }
        String orderId = data(call(post("/work-orders"), order)).path("workOrderId").asText();
        data(call(post("/work-orders/" + orderId + "/approve")));
        return new Fixture(itemId, stockId, orderId);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private record Fixture(String itemId, String stockId, String orderId) {
    }
}
