package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotTraceRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionSimulationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private LotTraceRepository lotTraceRepository;
    @Autowired private ProductionRunRepository productionRunRepository;

    @Test
    void nonPhysicalRunsCanRecordPlansWithoutMovingStockOrCompletingAnOrder() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "SIM-" + tag, "itemName", "Simulation " + tag, "unitId", "unit_kg")))
            .path("itemId").asText();
        String inventoryId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "quantity", 10, "location", "SIM-" + tag)))
            .path("inventoryId").asText();
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", "Simulation " + tag, "workflowId", DEMO_WORKFLOW,
            "targetItemId", itemId, "targetQuantity", 10))).path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        call(post("/work-orders/" + orderId + "/allocations"), Map.of(
            "lines", java.util.List.of(Map.of("itemId", itemId, "quantity", 3))))
            .andExpect(status().isOk());

        for (String type : new String[] {"simulation", "test", "dry_run"}) {
            String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
                "workflowId", DEMO_WORKFLOW, "workOrderId", orderId, "plannedOutputQty", 4, "runType", type)))
                .path("productionRunId").asText();
            String recordingId = data(call(post("/production-runs/" + runId + "/items"), Map.of(
                "itemId", itemId, "inventoryId", inventoryId, "direction", "input", "actualQty", 2,
                "plannedQty", 2, "unit", "kg"))).path("productionRunItemId").asText();
            call(get("/inventories/" + inventoryId))
                .andExpect(jsonPath("$.data.quantity").value(10.0))
                .andExpect(jsonPath("$.data.reservedQuantity").value(3.0));
            call(get("/work-orders/" + orderId + "/allocations"))
                .andExpect(jsonPath("$.data.allocations[0].consumedQuantity").value(0.0));
            assertTrue(inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(
                "production_run_item", recordingId).isEmpty());
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 4))
                .andExpect(status().isOk());
        }

        call(get("/work-orders/" + orderId))
            .andExpect(jsonPath("$.data.workOrderStatus").value("approved"))
            .andExpect(jsonPath("$.data.producedQuantity").value(0.0));
    }

    @Test
    void simulationOutputDoesNotCreateRealLotGenealogy() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String raw = lotItem("SIM-RAW-" + tag);
        String product = lotItem("SIM-OUT-" + tag);
        String rawLot = lot(raw, "RAW-" + tag);
        String productLot = lot(product, "OUT-" + tag);
        String rawStock = stock(raw, rawLot, "RAW-" + tag, 5);
        String productStock = stock(product, productLot, "OUT-" + tag, 0);
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", "simulation")))
            .path("productionRunId").asText();

        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", raw,
            "inventoryId", rawStock, "direction", "input", "actualQty", 2, "plannedQty", 2, "unit", "kg"))
            .andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", product,
            "inventoryId", productStock, "direction", "output", "actualQty", 1, "plannedQty", 1, "unit", "kg"))
            .andExpect(status().isOk());

        call(get("/inventories/" + rawStock)).andExpect(jsonPath("$.data.quantity").value(5.0));
        call(get("/inventories/" + productStock)).andExpect(jsonPath("$.data.quantity").value(0.0));
        call(get("/lots/" + productLot)).andExpect(jsonPath("$.data.productionRunId").value(nullValue()));
        assertFalse(lotTraceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(rawLot, productLot, runId));
    }

    @Test
    void approvedCorrectionOfSimulationDoesNotMoveStock() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "SIM-COR-" + tag, "itemName", "Simulation correction " + tag,
            "unitId", "unit_kg"))).path("itemId").asText();
        String inventoryId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "quantity", 5, "location", "SIM-COR-" + tag)))
            .path("inventoryId").asText();
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", "simulation")))
            .path("productionRunId").asText();
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1))
            .andExpect(status().isOk());
        String correctionId = data(call(post("/production-runs/" + runId + "/corrections"), Map.of(
            "reason", "Adjust scenario", "lines", java.util.List.of(Map.of("kind", "add_item",
                "direction", "input", "itemId", itemId, "inventoryId", inventoryId,
                "qty", 2, "unit", "kg")))))
            .path("productionRunCorrectionId").asText();
        JsonNode approved = data(call(post("/production-runs/" + runId + "/corrections/" + correctionId + "/approve")));
        String createdItemId = approved.path("lines").get(0).path("createdRunItemId").asText();

        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.quantity").value(5.0));
        assertTrue(inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(
            "production_run_item", createdItemId).isEmpty());
    }

    @Test
    void simulatedLotAllocationAndCancellationLeaveStockUntouched() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String itemId = lotItem("SIM-FEFO-" + tag);
        String lotId = lot(itemId, "FEFO-" + tag);
        String inventoryId = stock(itemId, lotId, "FEFO-" + tag, 5);
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", "simulation")))
            .path("productionRunId").asText();

        JsonNode allocated = data(call(post("/production-runs/" + runId + "/inputs/fefo"),
            Map.of("itemId", itemId, "quantity", 2, "unit", "kg")));
        String recordingId = allocated.get(0).path("productionRunItemId").asText();
        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.quantity").value(5.0));
        call(post("/production-runs/" + runId + "/items/" + recordingId + "/cancel"),
            Map.of("reason", "Revise scenario")).andExpect(status().isOk());

        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.quantity").value(5.0));
        assertTrue(inventoryTransactionRepository.findAllByReferenceTypeAndReferenceId(
            "production_run_item", recordingId).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"simulation, 2", "test, 2", "dry_run, 2", "actual, 0", "actual, 0.00001", "legacy_simulation, 1"})
    void correctionOnlyNamesRunsThatPhysicallyConsumedTheOutput(String runType, BigDecimal quantity) throws Exception {
        boolean legacyMovement = "legacy_simulation".equals(runType);
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String itemId = lotItem("SIM-TRACE-" + tag);
        String lotId = lot(itemId, "TRACE-" + tag);
        String inventoryId = stock(itemId, lotId, "TRACE-" + tag, 0);
        String producer = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 4))).path("productionRunId").asText();
        String output = data(call(post("/production-runs/" + producer + "/items"), Map.of("itemId", itemId,
            "inventoryId", inventoryId, "direction", "output", "plannedQty", 4, "actualQty", 4, "unit", "kg")))
            .path("productionRunItemId").asText();
        call(post("/production-runs/" + producer + "/finish"), Map.of("actualOutputQty", 4))
            .andExpect(status().isOk());

        JsonNode scenario = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", legacyMovement ? "actual" : runType)));
        call(post("/production-runs/" + scenario.path("productionRunId").asText() + "/items"), Map.of(
            "itemId", itemId, "inventoryId", inventoryId, "direction", "input", "plannedQty", 2, "actualQty", quantity, "unit", "kg"))
            .andExpect(status().isOk());
        if (legacyMovement) {
            // Older simulation recordings could move stock. Preserve that evidence in correction diagnostics.
            var legacyRun = productionRunRepository.findById(scenario.path("productionRunId").asText()).orElseThrow();
            legacyRun.setRunType("simulation");
            productionRunRepository.save(legacyRun);
        }
        JsonNode consumer = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1)));
        call(post("/production-runs/" + consumer.path("productionRunId").asText() + "/items"), Map.of(
            "itemId", itemId, "inventoryId", inventoryId, "direction", "input", "plannedQty", 3, "actualQty", 3, "unit", "kg"))
            .andExpect(status().isOk());
        String correction = data(call(post("/production-runs/" + producer + "/corrections"), Map.of(
            "reason", "Check physical consumers", "lines", java.util.List.of(Map.of("kind", "void_item",
                "targetRunItemId", output)))))
            .path("productionRunCorrectionId").asText();

        call(post("/production-runs/" + producer + "/corrections/" + correction + "/approve"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString(consumer.path("runNumber").asText())))
            .andExpect(jsonPath("$.message", legacyMovement
                ? containsString(scenario.path("runNumber").asText())
                : not(containsString(scenario.path("runNumber").asText()))));
        call(get("/inventories/" + inventoryId)).andExpect(jsonPath("$.data.quantity").value(legacyMovement ? 0.0 : 1.0));
        call(get("/production-runs/" + producer + "/corrections"))
            .andExpect(jsonPath("$.data[0].status").value("pending_approval"));
    }

    private String lotItem(String code) throws Exception {
        return data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", code,
            "itemName", code, "unitId", "unit_kg", "lotManageYn", "Y"))).path("itemId").asText();
    }

    private String lot(String itemId, String lotNo) throws Exception {
        return data(call(post("/lots"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "lotNo", lotNo))).path("lotId").asText();
    }

    private String stock(String itemId, String lotId, String location, int quantity) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "lotId", lotId, "location", location, "quantity", quantity))).path("inventoryId").asText();
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
}
