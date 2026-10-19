package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** F16 must also hold through the production-linked completion endpoint; no stock or BOM fixtures are needed. */
@AutoConfigureMockMvc
class ProductionStepExactSnapshotRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999.9999", "0.1234567890123456789"})
    void productionCompletionPreservesTheExactOutputAndTakesItsEqualQuantityBranch(String literal) throws Exception {
        BigDecimal quantity = new BigDecimal(literal);
        String project = data(post("/projects"), Map.of("projectName", "Production precision " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        String workflow = data(post("/workflows"), Map.of("projectId", project, "workflowName", "Exact output"))
            .path("workflowId").asText();
        String first = data(post("/processes"), Map.of("workflowId", workflow, "processName", "Source", "nodeType", "process"))
            .path("processId").asText();
        String second = data(post("/processes"), Map.of("workflowId", workflow, "processName", "Target", "nodeType", "process"))
            .path("processId").asText();
        data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", first, "toProcessId", second,
            "conditionExpr", "quantity = " + literal));
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        JsonNode started = data(post("/production-runs/start"), Map.of("projectId", project, "workflowId", workflow,
            "workflowRevisionId", revision, "plannedOutputQty", 1, "runType", "simulation"));
        assertTrue(started.path("bomId").isNull());
        String run = started.path("productionRunId").asText();
        String path = "/production-runs/" + run + "/steps";
        String step = data(get(path), null).get(0).path("stepId").asText();
        data(post(path + "/" + step + "/start"), null);

        JsonNode completed = data(post(path + "/" + step + "/complete"),
            Map.of("outputSnapshot", Map.of("quantity", quantity)));

        assertEquals(0, quantity.compareTo(completed.path("outputSnapshot").path("quantity").decimalValue()),
            "The production completion response must retain the original decimal");
        JsonNode steps = data(get(path), null);
        JsonNode target = null;
        for (JsonNode candidate : steps) if (second.equals(candidate.path("nodeId").asText())) target = candidate;
        assertTrue(target != null, "Exact equality must route the output to the target process");
        assertEquals(0, quantity.compareTo(target.path("inputSnapshot").path("quantity").decimalValue()));
        String stored = jdbc.queryForObject("select output_snapshot::text from flow_run_step where step_id=?", String.class, step);
        JsonNode storedOutput = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(stored);
        assertEquals(0, quantity.compareTo(storedOutput.path("quantity").decimalValue()));
        assertEquals(0, jdbc.queryForObject("select count(*) from production_run_item where production_run_id=?", Integer.class, run));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        var response = mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode envelope = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(response.getContentAsString());
        return envelope.path("data");
    }
}
