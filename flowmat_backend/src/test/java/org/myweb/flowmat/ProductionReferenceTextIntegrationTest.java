package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionReferenceTextIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest
    @ValueSource(strings = {"projectId", "workflowId", "targetItemId", "workOrderId"})
    void invalidStartReferencesDoNotStartAnOrderOrCreateARun(String field) throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = startBody(fixture);
        body.put(field, body.get(field) + "\u0000");
        call(post("/production-runs/start"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/work-orders/" + fixture.order())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderStatus").value("approved"))
            .andExpect(jsonPath("$.data.runCount").value(0));
        stock(fixture, 10.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"itemId", "inventoryId"})
    void invalidRecordingReferencesAreRejectedBeforeLookupOrStockMovement(String field) throws Exception {
        Fixture fixture = fixture();
        String run = start(fixture);
        Map<String, Object> body = recording(fixture);
        body.put(field, body.get(field) + "\u0000");
        call(post("/production-runs/" + run + "/items"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        stock(fixture, 10.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"itemId", "inventoryId"})
    void invalidCorrectionReferencesDoNotCreateAPendingRequest(String field) throws Exception {
        Fixture fixture = fixture();
        String run = start(fixture);
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        Map<String, Object> line = new LinkedHashMap<>(Map.of("kind", "add_item", "direction", "input",
            "itemId", fixture.item(), "inventoryId", fixture.inventory(), "qty", 1, "unit", "ea"));
        line.put(field, line.get(field) + "\u0000");
        call(post("/production-runs/" + run + "/corrections"), Map.of("reason", "Invalid reference", "lines", List.of(line)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        noCorrection(run);
        stock(fixture, 10.0);
    }

    @Test
    void invalidVoidedRecordingIdDoesNotAliasAnExistingRecording() throws Exception {
        Fixture fixture = fixture();
        String run = start(fixture);
        String recorded = data(call(post("/production-runs/" + run + "/items"), recording(fixture)))
            .path("productionRunItemId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/corrections"), Map.of("reason", "Invalid reference",
            "lines", List.of(Map.of("kind", "void_item", "targetRunItemId", recorded + "\u0000"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("targetRunItemId")));
        noCorrection(run);
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].cancelled").value(false));
        stock(fixture, 9.0);
        String correction = data(call(post("/production-runs/" + run + "/corrections"), Map.of("reason", "Valid reference",
            "lines", List.of(Map.of("kind", "void_item", "targetRunItemId", recorded)))))
            .path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + run + "/corrections/" + correction + "/approve"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("applied"));
        stock(fixture, 10.0);
    }

    @Test
    void invalidFefoItemIdDoesNotReachTheDatabaseOrConsumeALot() throws Exception {
        Fixture fixture = fixture(true);
        String run = start(fixture);
        call(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", fixture.item() + "\u0000",
            "quantity", 2, "unit", "ea")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("itemId")));
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        stock(fixture, 10.0);
        call(post("/production-runs/" + run + "/inputs/fefo"), Map.of("itemId", fixture.item(), "quantity", 2, "unit", "ea"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].itemId").value(fixture.item()));
        stock(fixture, 8.0);
    }

    private void noCorrection(String run) throws Exception {
        call(get("/production-runs/" + run + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/production-runs/" + run)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actualOutputQty").value(1.0));
    }

    private Map<String, Object> startBody(Fixture fixture) {
        return new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", fixture.item(), "workOrderId", fixture.order(), "plannedOutputQty", 1));
    }

    private Map<String, Object> recording(Fixture fixture) {
        return new LinkedHashMap<>(Map.of("itemId", fixture.item(), "inventoryId", fixture.inventory(),
            "direction", "input", "plannedQty", 1, "actualQty", 1, "unit", "ea"));
    }

    private String start(Fixture fixture) throws Exception {
        return data(call(post("/production-runs/start"), startBody(fixture))).path("productionRunId").asText();
    }

    private void stock(Fixture fixture, double quantity) throws Exception {
        call(get("/inventories/" + fixture.inventory())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(quantity))
            .andExpect(jsonPath("$.data.reservedQuantity").value(0.0));
    }

    private Fixture fixture() throws Exception {
        return fixture(false);
    }

    private Fixture fixture(boolean lotManaged) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "PROD-ID-" + tag,
            "itemName", "Production reference " + tag, "unitId", "unit_ea", "lotManageYn", lotManaged ? "Y" : "N")))
            .path("itemId").asText();
        Map<String, Object> stock = new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "quantity", 10, "location", "PROD-ID-" + tag));
        if (lotManaged) {
            stock.put("lotId", data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", item,
                "lotNo", "PROD-ID-" + tag))).path("lotId").asText());
        }
        String inventory = data(call(post("/inventories"), stock)).path("inventoryId").asText();
        String order = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Production reference " + tag, "targetItemId", item, "targetQuantity", 4)))
            .path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        return new Fixture(item, inventory, order);
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

    private record Fixture(String item, String inventory, String order) {}
}
