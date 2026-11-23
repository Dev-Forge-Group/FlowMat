package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionNullLineIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNullAllocationLinesAndAllowsASubsequentValidRequest(boolean precedingValidLine) throws Exception {
        Stock stock = stock();
        String order = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Null lines " + UUID.randomUUID(), "targetQuantity", 1))).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        Map<String, Object> valid = Map.of("itemId", stock.item(), "quantity", 2);
        call(post("/work-orders/" + order + "/allocations"), Map.of("lines", withNull(valid, precedingValidLine)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("lines")));
        call(get("/work-orders/" + order + "/allocations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.allocations", hasSize(0)));
        assertStock(stock, 10.0, 0.0);
        call(post("/work-orders/" + order + "/allocations"), Map.of("lines", List.of(valid)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.allocations", hasSize(1)));
        assertStock(stock, 10.0, 2.0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNullCorrectionLinesWithoutCreatingAPendingRequest(boolean precedingValidLine) throws Exception {
        Stock stock = stock();
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1))).path("productionRunId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        Map<String, Object> valid = Map.of("kind", "add_item", "direction", "input", "itemId", stock.item(),
            "inventoryId", stock.inventory(), "qty", 2, "unit", "ea");
        call(post("/production-runs/" + run + "/corrections"), Map.of("reason", "Invalid lines",
            "lines", withNull(valid, precedingValidLine)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("lines")));
        call(get("/production-runs/" + run + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/production-runs/" + run)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actualOutputQty").value(1.0));
        assertStock(stock, 10.0, 0.0);
        String correction = data(call(post("/production-runs/" + run + "/corrections"),
            Map.of("reason", "Valid lines", "lines", List.of(valid)))).path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + run + "/corrections/" + correction + "/approve"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("applied"));
        assertStock(stock, 8.0, 0.0);
    }

    private List<Map<String, Object>> withNull(Map<String, Object> valid, boolean precedingValidLine) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (precedingValidLine) lines.add(valid);
        lines.add(null);
        return lines;
    }

    private void assertStock(Stock stock, double quantity, double reservedQuantity) throws Exception {
        call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(quantity))
            .andExpect(jsonPath("$.data.reservedQuantity").value(reservedQuantity));
    }

    private Stock stock() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "NULL-LINE-" + tag, "itemName", "Null line " + tag, "unitId", "unit_ea")))
            .path("itemId").asText();
        String inventory = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "quantity", 10, "location", "NULL-LINE-" + tag))).path("inventoryId").asText();
        return new Stock(item, inventory);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }

    private record Stock(String item, String inventory) {}
}
