package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionRunQuantityIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void negativePlannedOutputDoesNotCreateARunOrStartItsOrder() throws Exception {
        String workflowId = workflow();
        String orderId = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "workOrderTitle", "Quantity validation", "targetQuantity", 2)))
            .path("workOrderId").asText();
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());

        call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflowId,
            "workOrderId", orderId, "plannedOutputQty", -1))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("plannedOutputQty")));
        call(get("/production-runs").param("workflowId", workflowId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/work-orders/" + orderId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.workOrderStatus").value("approved"));
    }

    @Test
    void negativeActualOutputDoesNotCloseTheRunAndZeroOutputRemainsValid() throws Exception {
        String runId = start(workflow());

        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", -1))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("actualOutputQty")));
        call(get("/production-runs/" + runId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.runStatus").value("running"))
            .andExpect(jsonPath("$.data.actualOutputQty").value(0.0));
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 0))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.actualOutputQty").value(0.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plannedQty", "actualQty"})
    void negativeRecordingDoesNotSaveOrMoveStock(String field) throws Exception {
        String runId = start(workflow());
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "QTY-" + tag, "itemName", "Quantity " + tag, "unitId", "unit_kg"))).path("itemId").asText();
        String stockId = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "location", "QTY-" + tag, "quantity", 10))).path("inventoryId").asText();

        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", itemId, "inventoryId", stockId,
            "direction", "input", "unit", "kg", "plannedQty", "plannedQty".equals(field) ? -1 : 1,
            "actualQty", "actualQty".equals(field) ? -1 : 1))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/inventories/" + stockId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(10.0));
    }

    @ParameterizedTest
    @CsvSource({
        "start, 10000000000", "start, 9999999999.99995",
        "finish, 10000000000", "finish, 9999999999.99995"
    })
    void outputOverflowIsRejectedWithoutChangingTheRun(String operation, BigDecimal quantity) throws Exception {
        String workflowId = workflow();
        String field = "start".equals(operation) ? "plannedOutputQty" : "actualOutputQty";
        if ("start".equals(operation)) {
            call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflowId, field, quantity))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
            call(get("/production-runs").param("workflowId", workflowId)).andExpect(jsonPath("$.data", hasSize(0)));
        } else {
            String runId = start(workflowId);
            call(post("/production-runs/" + runId + "/finish"), Map.of(field, quantity))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
            call(get("/production-runs/" + runId)).andExpect(jsonPath("$.data.runStatus").value("running"));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "plannedQty, 10000000000", "plannedQty, 9999999999.99995",
        "actualQty, 10000000000", "actualQty, 9999999999.99995"
    })
    void recordingOverflowIsRejectedBeforeStockChanges(String field, BigDecimal quantity) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_kg");
        String stockId = stock(itemId);
        call(post("/production-runs/" + runId + "/items"), Map.of("itemId", itemId, "inventoryId", stockId,
            "direction", "output", "unit", "kg", "plannedQty", "plannedQty".equals(field) ? quantity : BigDecimal.ONE,
            "actualQty", "actualQty".equals(field) ? quantity : BigDecimal.ONE))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        assertNoRecordingOrStockChange(runId, stockId);
    }

    @ParameterizedTest
    @CsvSource({
        "add_item, 10000000000", "add_item, 9999999999.99995",
        "set_output_qty, 10000000000", "set_output_qty, 9999999999.99995"
    })
    void correctionOverflowDoesNotCreateAPendingCorrection(String kind, BigDecimal quantity) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_kg");
        String stockId = stock(itemId);
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        String field = "add_item".equals(kind) ? "qty" : "afterQty";
        Map<String, ?> line = "add_item".equals(kind)
            ? Map.of("kind", kind, "direction", "output", "itemId", itemId, "inventoryId", stockId, "qty", quantity, "unit", "kg")
            : Map.of("kind", kind, "afterQty", quantity);
        call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Quantity overflow", "lines", List.of(line)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        call(get("/production-runs/" + runId + "/corrections")).andExpect(jsonPath("$.data", hasSize(0)));
        assertNoRecordingOrStockChange(runId, stockId);
    }

    @ParameterizedTest
    @CsvSource({"manual,input", "manual,output", "correction,input", "correction,output"})
    void convertedStockOverflowIsRejectedBeforeSaving(String operation, String direction) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_g");
        String stockId = stock(itemId);
        BigDecimal quantity = new BigDecimal("10000000"); // Fits the source column, exceeds the stock column in grams.
        if ("manual".equals(operation)) {
            call(post("/production-runs/" + runId + "/items"), Map.of("itemId", itemId, "inventoryId", stockId,
                "direction", direction, "unit", "kg", "plannedQty", quantity))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("stockQuantity")));
        } else {
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
            call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Converted quantity overflow", "lines", List.of(
                Map.of("kind", "add_item", "direction", direction, "itemId", itemId, "inventoryId", stockId,
                    "qty", quantity, "unit", "kg"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("stockQuantity")));
            call(get("/production-runs/" + runId + "/corrections")).andExpect(jsonPath("$.data", hasSize(0)));
        }
        assertNoRecordingOrStockChange(runId, stockId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "finish", "manual", "correction"})
    void theLargestRoundableQuantityRemainsValid(String operation) throws Exception {
        BigDecimal quantity = new BigDecimal("9999999999.99994");
        String workflowId = workflow();
        String runId = "start".equals(operation)
            ? data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflowId,
                "plannedOutputQty", quantity))).path("productionRunId").asText()
            : start(workflowId);
        BigDecimal stored;
        if ("manual".equals(operation)) {
            String recording = data(call(post("/production-runs/" + runId + "/items"), Map.of("itemId", item("unit_kg"),
                "direction", "output", "unit", "kg", "plannedQty", quantity))).path("productionRunItemId").asText();
            stored = jdbcTemplate.queryForObject("select planned_qty from production_run_item where production_run_item_id = ?",
                BigDecimal.class, recording);
        } else if ("correction".equals(operation)) {
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
            String correction = data(call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Boundary check",
                "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", quantity)))))
                .path("productionRunCorrectionId").asText();
            call(post("/production-runs/" + runId + "/corrections/" + correction + "/approve")).andExpect(status().isOk());
            stored = jdbcTemplate.queryForObject("select actual_output_qty from production_run where production_run_id = ?",
                BigDecimal.class, runId);
        } else {
            if ("finish".equals(operation)) {
                call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", quantity)).andExpect(status().isOk());
            }
            String column = "start".equals(operation) ? "planned_output_qty" : "actual_output_qty";
            stored = jdbcTemplate.queryForObject("select " + column + " from production_run where production_run_id = ?",
                BigDecimal.class, runId);
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("9999999999.9999").compareTo(stored));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "finish", "manual", "correction"})
    void theResponseUsesTheSameRoundedQuantityAsStorage(String operation) throws Exception {
        BigDecimal quantity = new BigDecimal("1.23455");
        String workflowId = workflow();
        if ("start".equals(operation)) {
            call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflowId,
                "plannedOutputQty", quantity)).andExpect(status().isOk()).andExpect(jsonPath("$.data.plannedOutputQty").value(1.2346));
            return;
        }
        String runId = start(workflowId);
        if ("finish".equals(operation)) {
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", quantity))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.actualOutputQty").value(1.2346));
        } else if ("manual".equals(operation)) {
            call(post("/production-runs/" + runId + "/items"), Map.of("itemId", item("unit_kg"), "direction", "output",
                "unit", "kg", "plannedQty", quantity, "actualQty", quantity)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plannedQty").value(1.2346)).andExpect(jsonPath("$.data.actualQty").value(1.2346));
        } else {
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
            call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Rounding check",
                "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", quantity)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].afterQty").value(1.2346));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "output"})
    void aRecordingRoundedToZeroInItsSourceUnitCannotMoveStock(String direction) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_g");
        String stockId = stock(itemId);
        String recording = data(call(post("/production-runs/" + runId + "/items"), Map.of("itemId", itemId,
            "inventoryId", stockId, "direction", direction, "unit", "kg", "plannedQty", new BigDecimal("0.00001"))))
            .path("productionRunItemId").asText();
        call(get("/production-runs/" + runId + "/items")).andExpect(jsonPath("$.data[0].plannedQty").value(0.0));
        call(get("/inventories/" + stockId)).andExpect(jsonPath("$.data.quantity").value(10.0));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from inventory_transaction where reference_type = 'production_run_item' and reference_id = ?",
            Integer.class, recording));
    }

    @ParameterizedTest
    @ValueSource(strings = {"input", "output"})
    void aCorrectionRoundedToZeroInItsSourceUnitIsRejected(String direction) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_g");
        String stockId = stock(itemId);
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Zero source quantity",
            "lines", List.of(Map.of("kind", "add_item", "direction", direction, "itemId", itemId, "inventoryId", stockId,
                "qty", new BigDecimal("0.00001"), "unit", "kg"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("qty")));
        call(get("/production-runs/" + runId + "/corrections")).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @ParameterizedTest
    @CsvSource({"manual,input", "manual,output", "correction,input", "correction,output"})
    void theSmallestRoundableSourceQuantityMovesItsRoundedEquivalent(String operation, String direction) throws Exception {
        String runId = start(workflow());
        String itemId = item("unit_g");
        String stockId = stock(itemId);
        BigDecimal source = new BigDecimal("0.00005"); // 0.0001 kg is stored, hence 0.1 g moves.
        String recording;
        if ("manual".equals(operation)) {
            recording = data(call(post("/production-runs/" + runId + "/items"), Map.of("itemId", itemId,
                "inventoryId", stockId, "direction", direction, "unit", "kg", "plannedQty", source)))
                .path("productionRunItemId").asText();
        } else {
            call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
            String correction = data(call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "Minimum quantity",
                "lines", List.of(Map.of("kind", "add_item", "direction", direction, "itemId", itemId, "inventoryId", stockId,
                    "qty", source, "unit", "kg"))))).path("productionRunCorrectionId").asText();
            recording = data(call(post("/production-runs/" + runId + "/corrections/" + correction + "/approve")))
                .path("lines").get(0).path("createdRunItemId").asText();
        }
        BigDecimal stored = jdbcTemplate.queryForObject("select planned_qty from production_run_item where production_run_item_id = ?",
            BigDecimal.class, recording);
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("0.0001").compareTo(stored));
        call(get("/inventories/" + stockId)).andExpect(jsonPath("$.data.quantity").value("input".equals(direction) ? 9.9 : 10.1));
    }

    @Test
    void anOutputCorrectionCannotRequestTheSameStoredValueWithExtraDecimals() throws Exception {
        String runId = start(workflow());
        call(post("/production-runs/" + runId + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/corrections"), Map.of("reason", "No effective change",
            "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", new BigDecimal("1.00001")))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("already")));
        call(get("/production-runs/" + runId + "/corrections")).andExpect(jsonPath("$.data", hasSize(0)));
    }

    private void assertNoRecordingOrStockChange(String runId, String stockId) throws Exception {
        call(get("/production-runs/" + runId + "/items")).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/inventories/" + stockId)).andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(10.0));
    }

    private String item(String unitId) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "QTY-" + tag,
            "itemName", "Quantity " + tag, "unitId", unitId))).path("itemId").asText();
    }

    private String stock(String itemId) throws Exception {
        return data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId,
            "location", "QTY-" + UUID.randomUUID(), "quantity", 10))).path("inventoryId").asText();
    }

    private String workflow() throws Exception {
        return data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Quantity " + UUID.randomUUID()))).path("workflowId").asText();
    }

    private String start(String workflowId) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "plannedOutputQty", 1))).path("productionRunId").asText();
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
