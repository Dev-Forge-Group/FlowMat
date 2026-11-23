package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionRunPortContractIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;

    @ParameterizedTest
    @CsvSource({"false, itemId", "false, direction", "true, itemId", "true, direction"})
    void mismatchedPortIsRejectedBeforeAnyRecordingOrStockMovement(boolean published, String field) throws Exception {
        Fixture fixture = fixture(published, "itemId".equals(field));
        int movementCount = inventoryTransactionRepository
            .findAllByInventoryIdOrderByCreatedAtDesc(fixture.inventoryId()).size();

        call(post("/production-runs/" + fixture.runId() + "/items"), recording(fixture,
            "direction".equals(field) ? "output" : "input"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(published ? "published revision" : field)));

        call(get("/production-runs/" + fixture.runId() + "/items"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/inventories/" + fixture.inventoryId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(10.0));
        assertEquals(movementCount, inventoryTransactionRepository
            .findAllByInventoryIdOrderByCreatedAtDesc(fixture.inventoryId()).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchingPortInfersItsProcessAndAllowsConvertedQuantities(boolean published) throws Exception {
        Fixture fixture = fixture(published, false);

        call(post("/production-runs/" + fixture.runId() + "/items"), recording(fixture, " INPUT "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.processId").value(fixture.processId()))
            .andExpect(jsonPath("$.data.processIoId").value(fixture.portId()))
            .andExpect(jsonPath("$.data.direction").value("input"));
        call(get("/inventories/" + fixture.inventoryId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(9.5));
    }

    private Fixture fixture(boolean published, boolean wrongPortItem) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String itemId = item("PORT-" + tag);
        String portItemId = wrongPortItem ? item("OTHER-" + tag) : itemId;
        String inventoryId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "quantity", 10, "location", "PORT-" + tag))).path("inventoryId").asText();
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Port contract " + tag))).path("workflowId").asText();
        String processId = data(call(post("/processes"), Map.of("workflowId", workflowId,
            "processName", "Port process " + tag))).path("processId").asText();
        String portId = data(call(post("/process-ios"), Map.of("processId", processId,
            "itemId", portItemId, "direction", "input", "quantity", 1, "unit", "kg", "requiredYn", "N")))
            .path("processIoId").asText();
        if (published) {
            data(call(post("/workflows/" + workflowId + "/revisions")));
        }
        JsonNode run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "plannedOutputQty", 1)));
        assertEquals(published, run.path("workflowRevisionId").isTextual());
        return new Fixture(run.path("productionRunId").asText(), processId, portId, itemId, inventoryId);
    }

    private String item(String code) throws Exception {
        return data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", code,
            "itemName", code, "unitId", "unit_kg"))).path("itemId").asText();
    }

    private Map<String, ?> recording(Fixture fixture, String direction) {
        return Map.of("processIoId", fixture.portId(), "inventoryId", fixture.inventoryId(),
            "itemId", fixture.itemId(), "direction", direction, "plannedQty", 500, "actualQty", 500, "unit", "g");
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

    private record Fixture(String runId, String processId, String portId, String itemId, String inventoryId) {
    }
}
