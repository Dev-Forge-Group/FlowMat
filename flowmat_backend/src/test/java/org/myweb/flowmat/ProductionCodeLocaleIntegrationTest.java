package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@ResourceLock("java.util.Locale")
class ProductionCodeLocaleIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void normalizesWorkOrderPriorityIndependentlyOfTheServerLocale() throws Exception {
        withTurkishLocale(() -> {
            Map<String, Object> body = Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "Locale " + UUID.randomUUID(),
                "targetQuantity", 1, "priority", " HIGH ");
            String order = data(call(post("/work-orders"), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value("high"))).path("workOrderId").asText();
            call(put("/work-orders/" + order), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value("high"));
        });
    }

    @Test
    void normalizesSimulationRunTypeIndependentlyOfTheServerLocale() throws Exception {
        withTurkishLocale(() -> call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", " SIMULATION "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.runType").value("simulation")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actual", "simulation"})
    void recordsUppercaseInputWithoutChangingItsStockSemantics(String runType) throws Exception {
        Stock stock = stock();
        String run = start(runType);
        withTurkishLocale(() -> {
            call(post("/production-runs/" + run + "/items"), Map.of("itemId", stock.item(),
                "inventoryId", stock.inventory(), "direction", " INPUT ", "plannedQty", 1, "actualQty", 1, "unit", "ea"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.direction").value("input"));
            call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value("actual".equals(runType) ? 9.0 : 10.0));
        });
    }

    @Test
    void normalizesCorrectionKindsAndDirectionBeforeApplyingStockMovements() throws Exception {
        Stock stock = stock();
        String run = start("actual");
        String recording = data(call(post("/production-runs/" + run + "/items"), Map.of("itemId", stock.item(),
            "inventoryId", stock.inventory(), "direction", "input", "plannedQty", 1, "actualQty", 1, "unit", "ea")))
            .path("productionRunItemId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        withTurkishLocale(() -> {
            JsonNode correction = data(call(post("/production-runs/" + run + "/corrections"), Map.of(
                "reason", "Correct uppercase codes", "lines", List.of(
                    Map.of("kind", " VOID_ITEM ", "targetRunItemId", recording),
                    Map.of("kind", " ADD_ITEM ", "direction", " INPUT ", "itemId", stock.item(),
                        "inventoryId", stock.inventory(), "qty", 2, "unit", "ea"),
                    Map.of("kind", " SET_OUTPUT_QTY ", "afterQty", 2)))));
            assertEquals("void_item", correction.path("lines").get(0).path("kind").asText());
            assertEquals("add_item", correction.path("lines").get(1).path("kind").asText());
            assertEquals("input", correction.path("lines").get(1).path("direction").asText());
            assertEquals("set_output_qty", correction.path("lines").get(2).path("kind").asText());
            call(post("/production-runs/" + run + "/corrections/"
                + correction.path("productionRunCorrectionId").asText() + "/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("applied"));
            call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(8.0));
            call(get("/production-runs/" + run)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actualOutputQty").value(2.0));
        });
    }

    @Test
    void aPriorityContainingNulCannotCreateOrReplaceAnOrder() throws Exception {
        String title = "Priority validation " + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT,
            "workOrderTitle", title, "targetQuantity", 1, "priority", "high\u0000"));
        int before = jdbcTemplate.queryForObject("select count(*) from work_order", Integer.class);
        call(post("/work-orders"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("priority")));
        assertEquals(before, jdbcTemplate.queryForObject("select count(*) from work_order", Integer.class));
        body.put("priority", "high");
        String order = data(call(post("/work-orders"), body)).path("workOrderId").asText();
        body.put("priority", "urgent\u0000");
        body.put("workOrderTitle", "Rejected replacement");
        call(put("/work-orders/" + order), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("priority")));
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.priority").value("high"))
            .andExpect(jsonPath("$.data.workOrderTitle").value(title));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actual", "simulation", "dry_run"})
    void aRunTypeContainingNulCannotStartAnExecution(String runType) throws Exception {
        int before = jdbcTemplate.queryForObject("select count(*) from production_run", Integer.class);
        call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1, "runType", runType + "\u0000")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("runType")));
        assertEquals(before, jdbcTemplate.queryForObject("select count(*) from production_run", Integer.class));
        call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1, "runType", " " + runType + " ")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runType").value(runType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actual", "simulation"})
    void aDirectionContainingNulCannotRecordAnItemOrMoveStock(String runType) throws Exception {
        Stock stock = stock();
        String run = start(runType);
        call(post("/production-runs/" + run + "/items"), recording(stock, "input\u0000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("direction")));
        call(get("/production-runs/" + run + "/items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        stockQuantity(stock, 10.0);
        call(post("/production-runs/" + run + "/items"), recording(stock, " INPUT ")).andExpect(status().isOk());
        stockQuantity(stock, "actual".equals(runType) ? 9.0 : 10.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"void_item", "add_item", "set_output_qty"})
    void aCorrectionKindContainingNulCannotCreateAnApprovalRequest(String kind) throws Exception {
        Stock stock = stock();
        FinishedRun run = finishedRun(stock);
        Map<String, Object> line = new LinkedHashMap<>(Map.of("kind", kind + "\u0000", "targetRunItemId", run.recording(),
            "direction", "input", "itemId", stock.item(), "inventoryId", stock.inventory(), "qty", 1,
            "unit", "ea", "afterQty", 2));
        call(post("/production-runs/" + run.id() + "/corrections"),
            Map.of("reason", "Validate correction code", "lines", List.of(line)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("kind")));
        noCorrection(run, stock);
        line.put("kind", kind);
        call(post("/production-runs/" + run.id() + "/corrections"),
            Map.of("reason", "Valid correction code", "lines", List.of(line))).andExpect(status().isOk());
    }

    @Test
    void aCorrectionDirectionContainingNulCannotCreateAnApprovalRequest() throws Exception {
        Stock stock = stock();
        FinishedRun run = finishedRun(stock);
        Map<String, Object> line = new LinkedHashMap<>(Map.of("kind", "add_item", "direction", "input\u0000",
            "itemId", stock.item(), "inventoryId", stock.inventory(), "qty", 1, "unit", "ea"));
        call(post("/production-runs/" + run.id() + "/corrections"),
            Map.of("reason", "Validate correction direction", "lines", List.of(line)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("direction")));
        noCorrection(run, stock);
        line.put("direction", " INPUT ");
        call(post("/production-runs/" + run.id() + "/corrections"),
            Map.of("reason", "Valid correction direction", "lines", List.of(line))).andExpect(status().isOk());
    }

    private Map<String, Object> recording(Stock stock, String direction) {
        return Map.of("itemId", stock.item(), "inventoryId", stock.inventory(), "direction", direction,
            "plannedQty", 1, "actualQty", 1, "unit", "ea");
    }

    private FinishedRun finishedRun(Stock stock) throws Exception {
        String run = start("actual");
        String recording = data(call(post("/production-runs/" + run + "/items"), recording(stock, "input")))
            .path("productionRunItemId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        return new FinishedRun(run, recording);
    }

    private void noCorrection(FinishedRun run, Stock stock) throws Exception {
        call(get("/production-runs/" + run.id() + "/corrections")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(get("/production-runs/" + run.id())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actualOutputQty").value(1.0));
        stockQuantity(stock, 9.0);
    }

    private void stockQuantity(Stock stock, double expected) throws Exception {
        call(get("/inventories/" + stock.inventory())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.quantity").value(expected));
    }

    private record FinishedRun(String id, String recording) {}

    private Stock stock() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String item = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT,
            "itemCode", "LOCALE-" + tag, "itemName", "Locale " + tag, "unitId", "unit_ea")))
            .path("itemId").asText();
        String inventory = data(call(post("/inventories"), Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "quantity", 10, "location", "LOCALE-" + tag))).path("inventoryId").asText();
        return new Stock(item, inventory);
    }

    private String start(String runType) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", DEMO_WORKFLOW, "plannedOutputQty", 1, "runType", runType)))
            .path("productionRunId").asText();
    }

    private void withTurkishLocale(CheckedAction action) throws Exception {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            action.run();
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
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
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
