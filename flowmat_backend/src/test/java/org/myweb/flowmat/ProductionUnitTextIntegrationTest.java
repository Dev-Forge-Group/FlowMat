package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionUnitTextIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    static Stream<Arguments> invalidUnits() {
        return Stream.of(Arguments.of("ea\u0000"), Arguments.of("e\u0000a"), Arguments.of("ea\ud800"),
            Arguments.of("ea\udc00"), Arguments.of("x".repeat(21)), Arguments.of("😀".repeat(21)),
            Arguments.of(" \u0001\t "));
    }

    @ParameterizedTest(name = "invalid recording unit case {index}")
    @MethodSource("invalidUnits")
    void rejectsInvalidRecordingUnitsBeforeSavingOrMovingStock(String unit) throws Exception {
        Stock stock = stock(false);
        String run = start();
        call(post("/production-runs/" + run + "/items"), recording(stock, unit))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        unchanged(run, stock);
    }

    @ParameterizedTest(name = "invalid correction unit case {index}")
    @MethodSource("invalidUnits")
    void rejectsInvalidCorrectionUnitsBeforeCreatingAnApprovalRequest(String unit) throws Exception {
        Stock stock = stock(false);
        String run = start();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/corrections"), correction(stock, unit))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        call(get("/production-runs/" + run + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        unchanged(run, stock);
        call(get("/production-runs/" + run)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actualOutputQty").value(1.0));
    }

    @ParameterizedTest(name = "invalid allocation unit case {index}")
    @MethodSource("invalidUnits")
    void rejectsInvalidFefoUnitsBeforeAnyLotIsConsumed(String unit) throws Exception {
        Stock stock = stock(false);
        String run = start();
        call(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", stock.item(), "quantity", 1, "unit", unit))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        unchanged(run, stock);
    }

    @Test
    void aKnownUnitCannotHideANulCharacterByTrimming() throws Exception {
        Stock stock = stock(true);
        String run = start();
        call(post("/production-runs/" + run + "/items"), recording(stock, "ea\u0000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        call(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", stock.item(), "quantity", 1, "unit", "ea\u0000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/corrections"), correction(stock, "ea\u0000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("unit")));
        unchanged(run, stock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "😀"})
    void acceptsTwentyUnicodeCharactersInRecordingCorrectionAndFefo(String character) throws Exception {
        Stock stock = stock(false);
        String unit = character.repeat(20);
        String run = start();
        call(post("/production-runs/" + run + "/items"), recording(stock, " " + unit + " "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unit").value(unit));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        String correction = data(call(post("/production-runs/" + run + "/corrections"), correction(stock, " " + unit + " "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].unit").value(unit)))
            .path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + run + "/corrections/" + correction + "/approve"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("applied"));
        String allocationRun = start();
        call(post("/production-runs/" + allocationRun + "/inputs/fefo"), Map.of(
            "itemId", stock.item(), "quantity", 2, "unit", " " + unit + " "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].unit").value(unit));
        call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(6.0));
    }

    private void unchanged(String run, Stock stock) throws Exception {
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(10.0));
    }

    private Map<String, Object> recording(Stock stock, String unit) {
        return Map.of("itemId", stock.item(), "inventoryId", stock.inventory(), "direction", "input",
            "plannedQty", 1, "actualQty", 1, "unit", unit);
    }

    private Map<String, Object> correction(Stock stock, String unit) {
        return Map.of("reason", "Validate recorded unit", "lines", List.of(Map.of("kind", "add_item", "direction", "input",
            "itemId", stock.item(), "inventoryId", stock.inventory(), "qty", 1, "unit", unit)));
    }

    private Stock stock(boolean knownUnit) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT,
            "itemCode", "UNIT-TEXT-" + tag, "itemName", "Unit text " + tag, "lotManageYn", "Y"));
        if (knownUnit) body.put("unitId", "unit_ea");
        String item = data(call(post("/items"), body)).path("itemId").asText();
        String lot = data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "lotNo", "UNIT-TEXT-" + tag))).path("lotId").asText();
        String inventory = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "lotId", lot, "quantity", 10, "location", "UNIT-TEXT-" + tag)))
            .path("inventoryId").asText();
        return new Stock(item, inventory);
    }

    private String start() throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
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
