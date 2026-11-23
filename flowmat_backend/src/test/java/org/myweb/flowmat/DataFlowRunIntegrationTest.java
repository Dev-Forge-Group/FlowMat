package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A non-manufacturing flow on the shared definition and execution core
 * (docs/architecture/adr/ADR-003-resource-port-contract.md, decision 9-(a)): File → Transform → Data with item-less ports
 * (V42), schema contracts and validation rules, run by an external executor that reports each step's result. The project
 * is new and empty, so any item, stock, LOT, BOM or production row would have been made by this flow.
 */
@AutoConfigureMockMvc
class DataFlowRunIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Map<String, Object> FILE_SCHEMA = Map.of("type", "object",
        "properties", Map.of("path", Map.of("type", "string"), "rows", Map.of("type", "number")),
        "required", List.of("path", "rows"));
    private static final Map<String, Object> DATA_SCHEMA = Map.of("type", "object",
        "properties", Map.of("rows", Map.of("type", "number"), "valid", Map.of("type", "boolean")),
        "required", List.of("rows", "valid"));

    @Test
    void aFileToDataFlowRunsWithoutAnyManufacturingRecord() throws Exception {
        String project = data(post("/projects"), Map.of("projectName", "Data flow " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        String workflow = data(post("/workflows"), Map.of("projectId", project, "workflowName", "CSV import"))
            .path("workflowId").asText();
        String read = process(workflow, "Read file");
        String transform = process(workflow, "Transform");
        String write = process(workflow, "Write dataset");

        String readOut = port(read, "output", "file", FILE_SCHEMA, null);
        String transformIn = port(transform, "input", "file", FILE_SCHEMA, null);
        String transformOut = port(transform, "output", "data", DATA_SCHEMA, "attrs.rows > 0");
        String writeIn = port(write, "input", "data", DATA_SCHEMA, "attrs.valid = true");
        connect(workflow, read, transform, readOut, transformIn);
        connect(workflow, transform, write, transformOut, writeIn);
        assertEquals(4, jdbcTemplate.queryForObject(
            "SELECT count(*) FROM process_io WHERE process_id IN (?, ?, ?) AND item_id IS NULL",
            Integer.class, read, transform, write));

        mockMvc.perform(auth(get("/workflows/" + workflow + "/validation")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.warnings").value(0));
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();

        JsonNode run = data(post("/flow-runs/graph"), Map.of("workflowId", workflow, "workflowRevisionId", revision,
            "runType", "actual", "inputPayload", Map.of("source", "in.csv")));
        String runId = run.path("flowRunId").asText();
        assertFalse(run.hasNonNull("productionRunId"));

        JsonNode roots = steps(runId);
        assertEquals(1, roots.size());
        assertEquals(read, roots.get(0).path("nodeId").asText());
        String readStep = roots.get(0).path("stepId").asText();
        complete(runId, readStep, "{\"attrs\":{\"path\":\"in.csv\",\"rows\":3}}");

        JsonNode afterRead = steps(runId);
        assertEquals(2, afterRead.size());
        JsonNode transformStep = afterRead.get(1);
        assertEquals(transform, transformStep.path("nodeId").asText());
        assertEquals(readStep, transformStep.path("sourceStepId").asText());
        assertEquals(3, transformStep.path("inputSnapshot").path("attrs").path("rows").asInt());
        String transformStepId = transformStep.path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + transformStepId + "/start"), null);

        // The output and input port contracts are checked before anything is recorded.
        rejected(runId, transformStepId, "{\"attrs\":{\"rows\":3}}",
            "Port " + transformOut + " requires outputSnapshot.attrs.valid.");
        rejected(runId, transformStepId, "{\"attrs\":{\"rows\":\"3\",\"valid\":true}}",
            "Port " + transformOut + " outputSnapshot.attrs.rows must be number.");
        rejected(runId, transformStepId, "{\"attrs\":{\"rows\":0,\"valid\":true}}",
            "Port " + transformOut + " validationRule did not pass.");
        rejected(runId, transformStepId, "{\"attrs\":{\"rows\":3,\"valid\":false}}",
            "Port " + writeIn + " validationRule did not pass.");
        assertEquals("running", steps(runId).get(1).path("status").asText());
        data(post("/flow-runs/" + runId + "/steps/" + transformStepId + "/complete"),
            mapper.readTree("{\"outputSnapshot\":{\"attrs\":{\"rows\":3,\"valid\":true}}}"));

        JsonNode afterTransform = steps(runId);
        assertEquals(3, afterTransform.size());
        assertEquals(write, afterTransform.get(2).path("nodeId").asText());
        assertEquals(transformStepId, afterTransform.get(2).path("sourceStepId").asText());
        complete(runId, afterTransform.get(2).path("stepId").asText(), "{\"attrs\":{\"stored\":3}}");
        assertEquals("finished", data(post("/flow-runs/" + runId + "/finish"), Map.of()).path("status").asText());

        for (JsonNode step : steps(runId)) {
            assertEquals("completed", step.path("status").asText());
            assertEquals(1, data(get("/flow-runs/" + runId + "/steps/" + step.path("stepId").asText() + "/attempts"), null)
                .size());
        }
        Map<String, Integer> events = new HashMap<>();
        for (JsonNode event : data(get("/flow-runs/" + runId + "/events"), null)) {
            events.merge(event.path("eventType").asText(), 1, Integer::sum);
        }
        assertEquals(Map.of("run_started", 1, "step_created", 3, "step_started", 3, "step_completed", 3,
            "run_finished", 1), events);

        List<String> manufacturing = new ArrayList<>();
        for (String table : List.of("item", "inventory", "inventory_transaction", "lot_master", "bom_header",
            "work_order", "production_run")) {
            Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE project_id = ?",
                Integer.class, project);
            if (rows != null && rows > 0) manufacturing.add(table + "=" + rows);
        }
        assertEquals(List.of(), manufacturing);
    }

    @Test
    void aPortItemIsAnOptionalBindingThatCanBeSetAndCleared() throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Binding " + UUID.randomUUID())).path("workflowId").asText();
        String process = process(workflow, "Mix");
        String portId = port(process, "output", "material", null, null);
        assertFalse(data(get("/process-ios/" + portId), null).hasNonNull("itemId"));

        assertEquals("itm_demo_mix_output", data(put("/process-ios/" + portId),
            Map.of("itemId", "itm_demo_mix_output")).path("itemId").asText());
        mockMvc.perform(auth(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("itemId", "itm_demo_mix_output", "clearItem", true)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("itemId cannot be supplied with clearItem."));
        // A blank itemId still leaves the binding alone; only clearItem removes it.
        assertEquals("itm_demo_mix_output", data(put("/process-ios/" + portId), Map.of("itemId", " "))
            .path("itemId").asText());
        assertFalse(data(put("/process-ios/" + portId), Map.of("clearItem", true)).hasNonNull("itemId"));

        mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(portBody(process, "output", "material", null, null, "no-such-item")))))
            .andExpect(status().isNotFound());
    }

    private String process(String workflow, String name) throws Exception {
        return data(post("/processes"), Map.of("workflowId", workflow, "processName", name)).path("processId").asText();
    }

    private String port(String process, String direction, String resourceType, Map<String, Object> schema,
        String rule) throws Exception {
        return data(post("/process-ios"), portBody(process, direction, resourceType, schema, rule, null))
            .path("processIoId").asText();
    }

    private Map<String, Object> portBody(String process, String direction, String resourceType,
        Map<String, Object> schema, String rule, String itemId) {
        Map<String, Object> body = new HashMap<>(Map.of("processId", process, "ioName", resourceType + " " + direction,
            "direction", direction, "resourceType", resourceType, "quantity", 0, "unit", "ea", "requiredYn", "Y"));
        if (schema != null) body.put("schemaJson", schema);
        if (rule != null) body.put("validationRule", rule);
        if (itemId != null) body.put("itemId", itemId);
        return body;
    }

    private void connect(String workflow, String from, String to, String fromIo, String toIo) throws Exception {
        data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", from, "toProcessId", to,
            "fromIoId", fromIo, "toIoId", toIo));
    }

    private JsonNode steps(String runId) throws Exception {
        return data(get("/flow-runs/" + runId + "/steps"), null);
    }

    private void complete(String runId, String stepId, String output) throws Exception {
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/start"), null);
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/complete"),
            mapper.readTree("{\"outputSnapshot\":" + output + "}"));
    }

    private void rejected(String runId, String stepId, String output, String message) throws Exception {
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + stepId + "/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":" + output + "}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(message));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
