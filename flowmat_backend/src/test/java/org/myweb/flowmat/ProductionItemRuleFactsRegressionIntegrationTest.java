package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionItemRuleFactsRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"itemType", "storageCondition"})
    void runStartEvaluatesItemPropertiesOutsideTheCatalogView(String property) throws Exception {
        Fixture fixture = fixture(property);
        String ruleName = "Start rule " + fixture.tag();
        rules(fixture, "run_start", property, ruleName);

        JsonNode refused = call(post("/production-runs/start"), Map.of(
            "projectId", fixture.projectId(), "workflowId", fixture.workflowId(),
            "targetItemId", fixture.matchingItemId(), "plannedOutputQty", 1), 400);

        assertEquals(ruleName, refused.path("message").asText(), refused.toString());
        assertEquals(0, runs(fixture).size());
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from production_run where project_id = ?", Integer.class, fixture.projectId()));

        JsonNode started = call(post("/production-runs/start"), Map.of(
            "projectId", fixture.projectId(), "workflowId", fixture.workflowId(),
            "targetItemId", fixture.otherItemId(), "plannedOutputQty", 1), 200).path("data");

        assertEquals("running", started.path("runStatus").asText());
        assertEquals(fixture.otherItemId(), started.path("targetItemId").asText());
        assertEquals(1, runs(fixture).size());
        assertEquals(fixture.otherItemId(), jdbc.queryForObject(
            "select target_item_id from production_run where production_run_id = ?",
            String.class, started.path("productionRunId").asText()));
    }

    @ParameterizedTest
    @CsvSource({"itemType,input", "itemType,output", "storageCondition,input", "storageCondition,output"})
    void itemRecordingEvaluatesItemPropertiesBeforeSavingOrMovingStock(String property, String direction)
        throws Exception {
        Fixture fixture = fixture(property);
        String matchingStock = stock(fixture.projectId(), fixture.matchingItemId(), fixture.tag() + "-match");
        String otherStock = stock(fixture.projectId(), fixture.otherItemId(), fixture.tag() + "-other");
        String ruleName = "Recording rule " + fixture.tag();
        rules(fixture, "run_item_record", property, ruleName);
        String runId = call(post("/production-runs/start"), Map.of(
            "projectId", fixture.projectId(), "workflowId", fixture.workflowId(), "plannedOutputQty", 1), 200)
            .path("data").path("productionRunId").asText();

        JsonNode refused = call(post("/production-runs/" + runId + "/items"), recording(
            fixture.matchingItemId(), matchingStock, direction), 400);

        assertEquals(ruleName, refused.path("message").asText(), refused.toString());
        assertEquals(0, runItems(runId).size());
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from production_run_item where production_run_id = ?", Integer.class, runId));
        assertStock(matchingStock, "10");
        assertStock(otherStock, "10");
        assertEquals(0, productionMovements(matchingStock));
        assertEquals(0, productionMovements(otherStock));

        JsonNode recorded = call(post("/production-runs/" + runId + "/items"), recording(
            fixture.otherItemId(), otherStock, direction), 200).path("data");

        assertEquals(fixture.otherItemId(), recorded.path("itemId").asText());
        assertEquals(direction, recorded.path("direction").asText());
        assertEquals(1, runItems(runId).size());
        assertEquals(fixture.otherItemId(), jdbc.queryForObject(
            "select item_id from production_run_item where production_run_id = ?", String.class, runId));
        assertStock(matchingStock, "10");
        assertStock(otherStock, "input".equals(direction) ? "8" : "12");
        assertEquals(0, productionMovements(matchingStock));
        assertEquals(1, productionMovements(otherStock));
    }

    private Fixture fixture(String property) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String projectId = call(post("/projects"), Map.of(
            "projectName", "Production rule facts " + tag, "ownerId", DEMO_OWNER), 200)
            .path("data").path("projectId").asText();
        String workflowId = call(post("/workflows"), Map.of(
            "projectId", projectId, "workflowName", "Rule facts " + tag), 200)
            .path("data").path("workflowId").asText();
        String matching = item(projectId, "PRF-M-" + tag, "material", "Frozen");
        String other = item(projectId, "PRF-O-" + tag,
            "itemType".equals(property) ? "product" : "material",
            "storageCondition".equals(property) ? "Dry" : "Frozen");
        return new Fixture(tag, projectId, workflowId, matching, other);
    }

    private String item(String projectId, String code, String type, String condition) throws Exception {
        return call(post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code,
            "itemType", type, "unitId", "unit_kg", "details", Map.of("storageCondition", condition)), 200)
            .path("data").path("itemId").asText();
    }

    private String stock(String projectId, String itemId, String location) throws Exception {
        return call(post("/inventories"), Map.of("projectId", projectId, "itemId", itemId,
            "location", location, "quantity", 10), 200).path("data").path("inventoryId").asText();
    }

    private void rules(Fixture fixture, String operation, String property, String ruleName) throws Exception {
        String expected = "itemType".equals(property) ? "material" : "Frozen";
        String expression = "operation == '" + operation + "' && item." + property + " == '" + expected + "'";
        for (String itemId : new String[] {fixture.matchingItemId(), fixture.otherItemId()}) {
            call(post("/flow-rules"), Map.of("projectId", fixture.projectId(), "targetType", "item",
                "targetId", itemId, "ruleName", ruleName, "conditionExpression", expression,
                "actionType", "block", "enabledYn", "Y"), 200);
        }
    }

    private Map<String, Object> recording(String itemId, String inventoryId, String direction) {
        return Map.of("itemId", itemId, "inventoryId", inventoryId, "direction", direction,
            "plannedQty", 5, "actualQty", 2, "unit", "kg");
    }

    private JsonNode runs(Fixture fixture) throws Exception {
        return call(get("/production-runs").param("workflowId", fixture.workflowId()), null, 200).path("data");
    }

    private JsonNode runItems(String runId) throws Exception {
        return call(get("/production-runs/" + runId + "/items"), null, 200).path("data");
    }

    private void assertStock(String inventoryId, String expected) throws Exception {
        JsonNode stock = call(get("/inventories/" + inventoryId), null, 200).path("data");
        assertEquals(0, new BigDecimal(expected).compareTo(stock.path("quantity").decimalValue()), stock.toString());
        BigDecimal stored = jdbc.queryForObject(
            "select quantity from inventory where inventory_id = ?", BigDecimal.class, inventoryId);
        assertEquals(0, new BigDecimal(expected).compareTo(stored));
    }

    private int productionMovements(String inventoryId) {
        return jdbc.queryForObject("select count(*) from inventory_transaction where inventory_id = ? "
            + "and reference_type = 'production_run_item'", Integer.class, inventoryId);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expectedStatus) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expectedStatus, response.getStatus(), parsed.toString());
        return parsed;
    }

    private record Fixture(String tag, String projectId, String workflowId, String matchingItemId, String otherItemId) {
    }
}
