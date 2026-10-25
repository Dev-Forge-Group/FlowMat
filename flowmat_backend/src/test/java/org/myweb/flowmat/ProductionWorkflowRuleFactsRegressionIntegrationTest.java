package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProductionWorkflowRuleFactsRegressionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void runStartEvaluatesWorkflowNameOutsideTheTypedView() throws Exception {
        Fixture fixture = fixture("Blocked workflow facts");
        String allowedWorkflow = workflow(fixture.projectId(), "Allowed workflow facts");
        String ruleName = "Workflow name blocks production start";
        block(fixture, "project", fixture.projectId(), ruleName,
            "operation == 'run_start' && workflow.workflowName == 'Blocked workflow facts'");

        JsonNode refused = start(fixture, fixture.workflowId(), null, 400);

        assertEquals(ruleName, refused.path("message").asText(), refused.toString());
        assertEquals(0, jdbc.queryForObject("select count(*) from production_run where project_id = ?",
            Integer.class, fixture.projectId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from production_run_item i "
            + "join production_run r on r.production_run_id = i.production_run_id where r.project_id = ?",
            Integer.class, fixture.projectId()));
        assertStock(fixture, "10", 0);

        JsonNode started = start(fixture, allowedWorkflow, null, 200).path("data");

        assertEquals("running", started.path("runStatus").asText());
        assertEquals(allowedWorkflow, started.path("workflowId").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from production_run where project_id = ?",
            Integer.class, fixture.projectId()));
        assertEquals(allowedWorkflow, jdbc.queryForObject(
            "select workflow_id from production_run where production_run_id = ?", String.class,
            started.path("productionRunId").asText()));
        assertEquals(0, runItems(started.path("productionRunId").asText()).size());
        assertStock(fixture, "10", 0);
    }

    @Test
    void itemRecordingUsesPublishedProcessNamesAfterTheDraftNamesChange() throws Exception {
        Fixture fixture = fixture("Process rule facts");
        String blocked = process(fixture, "Blocked process facts");
        String allowed = process(fixture, "Allowed process facts");
        String ruleName = "Published process name blocks recording";
        block(fixture, "workflow", fixture.workflowId(), ruleName,
            "operation == 'run_item_record' && process.processName == 'Blocked process facts'");
        String runId = publishedRun(fixture);

        assertEquals("Allowed process facts", call(put("/processes/" + blocked),
            Map.of("processName", "Allowed process facts"), 200).path("data").path("processName").asText());
        assertEquals("Blocked process facts", call(put("/processes/" + allowed),
            Map.of("processName", "Blocked process facts"), 200).path("data").path("processName").asText());

        assertRecordingRule(fixture, runId, ruleName,
            recording(fixture, blocked, null), recording(fixture, allowed, null));
    }

    @Test
    void itemRecordingUsesPublishedPortQuantitiesAfterTheDraftQuantitiesChange() throws Exception {
        Fixture fixture = fixture("Port rule facts");
        String process = process(fixture, "Port facts process");
        BigDecimal matching = new BigDecimal("9.1250");
        BigDecimal other = new BigDecimal("3.7500");
        String blocked = port(fixture, process, matching);
        String allowed = port(fixture, process, other);
        String ruleName = "Published port quantity blocks recording";
        block(fixture, "workflow", fixture.workflowId(), ruleName,
            "operation == 'run_item_record' && processIo.quantity == 9.125");
        String runId = publishedRun(fixture);

        assertEquals(0, other.compareTo(call(put("/process-ios/" + blocked), Map.of("quantity", other), 200)
            .path("data").path("quantity").decimalValue()));
        assertEquals(0, matching.compareTo(call(put("/process-ios/" + allowed), Map.of("quantity", matching), 200)
            .path("data").path("quantity").decimalValue()));

        assertRecordingRule(fixture, runId, ruleName,
            recording(fixture, process, blocked), recording(fixture, process, allowed));
    }

    private void assertRecordingRule(Fixture fixture, String runId, String ruleName,
        Map<String, Object> matching, Map<String, Object> other) throws Exception {
        JsonNode refused = call(post("/production-runs/" + runId + "/items"), matching, 400);

        assertEquals(ruleName, refused.path("message").asText(), refused.toString());
        assertEquals(0, runItems(runId).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from production_run_item where production_run_id = ?",
            Integer.class, runId));
        assertStock(fixture, "10", 0);

        JsonNode recorded = call(post("/production-runs/" + runId + "/items"), other, 200).path("data");

        assertEquals(fixture.itemId(), recorded.path("itemId").asText());
        assertEquals("input", recorded.path("direction").asText());
        assertEquals(other.get("processId"), recorded.path("processId").asText());
        if (other.containsKey("processIoId")) {
            assertEquals(other.get("processIoId"), recorded.path("processIoId").asText());
            assertEquals(other.get("processIoId"), jdbc.queryForObject(
                "select process_io_id from production_run_item where production_run_id = ?", String.class, runId));
        }
        assertEquals(1, runItems(runId).size());
        assertEquals(1, jdbc.queryForObject("select count(*) from production_run_item where production_run_id = ?",
            Integer.class, runId));
        assertEquals(other.get("processId"), jdbc.queryForObject(
            "select process_id from production_run_item where production_run_id = ?", String.class, runId));
        assertStock(fixture, "8", 1);
    }

    private Fixture fixture(String workflowName) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String projectId = call(post("/projects"), Map.of(
            "projectName", "Workflow rule facts " + tag, "ownerId", DEMO_OWNER), 200)
            .path("data").path("projectId").asText();
        String workflowId = workflow(projectId, workflowName);
        String itemId = call(post("/items"), Map.of("projectId", projectId, "itemCode", "WFR-" + tag,
            "itemName", "Workflow facts material", "itemType", "material", "unitId", "unit_kg"), 200)
            .path("data").path("itemId").asText();
        String stockId = call(post("/inventories"), Map.of("projectId", projectId, "itemId", itemId,
            "location", "Workflow facts " + tag, "quantity", 10), 200)
            .path("data").path("inventoryId").asText();
        return new Fixture(projectId, workflowId, itemId, stockId);
    }

    private String workflow(String projectId, String name) throws Exception {
        return call(post("/workflows"), Map.of("projectId", projectId, "workflowName", name), 200)
            .path("data").path("workflowId").asText();
    }

    private String process(Fixture fixture, String name) throws Exception {
        return call(post("/processes"), Map.of("workflowId", fixture.workflowId(), "processName", name), 200)
            .path("data").path("processId").asText();
    }

    private String port(Fixture fixture, String processId, BigDecimal quantity) throws Exception {
        return call(post("/process-ios"), Map.of("processId", processId, "itemId", fixture.itemId(),
            "direction", "input", "quantity", quantity, "unit", "kg", "requiredYn", "N"), 200)
            .path("data").path("processIoId").asText();
    }

    private String publishedRun(Fixture fixture) throws Exception {
        String revision = call(post("/workflows/" + fixture.workflowId() + "/revisions"), null, 200)
            .path("data").path("workflowRevisionId").asText();
        JsonNode started = start(fixture, fixture.workflowId(), revision, 200).path("data");
        assertEquals(revision, started.path("workflowRevisionId").asText());
        return started.path("productionRunId").asText();
    }

    private JsonNode start(Fixture fixture, String workflowId, String revision, int status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", fixture.projectId(),
            "workflowId", workflowId, "plannedOutputQty", 1));
        if (revision != null) body.put("workflowRevisionId", revision);
        return call(post("/production-runs/start"), body, status);
    }

    private void block(Fixture fixture, String targetType, String targetId, String name, String expression)
        throws Exception {
        call(post("/flow-rules"), Map.of("projectId", fixture.projectId(), "targetType", targetType,
            "targetId", targetId, "ruleName", name, "conditionExpression", expression,
            "actionType", "block", "enabledYn", "Y"), 200);
    }

    private Map<String, Object> recording(Fixture fixture, String processId, String portId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("itemId", fixture.itemId(),
            "inventoryId", fixture.stockId(), "processId", processId, "direction", "input",
            "plannedQty", 5, "actualQty", 2, "unit", "kg"));
        if (portId != null) body.put("processIoId", portId);
        return body;
    }

    private JsonNode runItems(String runId) throws Exception {
        return call(get("/production-runs/" + runId + "/items"), null, 200).path("data");
    }

    private void assertStock(Fixture fixture, String expectedQuantity, int expectedMovements) throws Exception {
        BigDecimal expected = new BigDecimal(expectedQuantity);
        JsonNode stock = call(get("/inventories/" + fixture.stockId()), null, 200).path("data");
        assertEquals(0, expected.compareTo(stock.path("quantity").decimalValue()), stock.toString());
        assertEquals(0, expected.compareTo(jdbc.queryForObject(
            "select quantity from inventory where inventory_id = ?", BigDecimal.class, fixture.stockId())));
        assertEquals(expectedMovements, jdbc.queryForObject("select count(*) from inventory_transaction "
            + "where inventory_id = ? and reference_type = 'production_run_item'", Integer.class, fixture.stockId()));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expectedStatus) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expectedStatus, response.getStatus(), parsed.toString());
        return parsed;
    }

    private record Fixture(String projectId, String workflowId, String itemId, String stockId) {
    }
}
