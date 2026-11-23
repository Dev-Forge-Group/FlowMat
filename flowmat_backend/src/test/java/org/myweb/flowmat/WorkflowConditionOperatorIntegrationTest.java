package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WorkflowConditionOperatorIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anInvalidValidationRuleCannotCreateAPortAndCorrectEqualityCan() throws Exception {
        Graph graph = graph();
        Map<String, Object> body = port(graph.source(), "output");
        body.put("validationRule", "quantity == 1");
        call(post("/process-ios"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("==")));
        call(get("/process-ios").param("processId", graph.source())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        body.put("validationRule", "quantity = 1");
        call(post("/process-ios"), body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.validationRule").value("quantity = 1"));
    }

    @Test
    void anInvalidPortUpdatePreservesTheRuleAndOtherChanges() throws Exception {
        Graph graph = graph();
        Map<String, Object> body = port(graph.source(), "output");
        body.put("validationRule", "quantity = 1");
        String portId = data(call(post("/process-ios"), body)).path("processIoId").asText();
        call(put("/process-ios/" + portId), Map.of("validationRule", "quantity == 2", "ioName", "Rejected"))
            .andExpect(status().isBadRequest());
        call(get("/process-ios/" + portId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.validationRule").value("quantity = 1"))
            .andExpect(jsonPath("$.data.ioName").value("Original"));
        call(put("/process-ios/" + portId), Map.of("validationRule", "quantity = 2", "ioName", "Updated"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ioName").value("Updated"));
    }

    @Test
    void anInvalidConditionCannotCreateAConnectionAndCorrectEqualityCan() throws Exception {
        Graph graph = graph();
        Map<String, Object> body = connection(graph);
        body.put("conditionExpr", "unit == 'kg'");
        call(post("/process-connections"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("==")));
        call(get("/process-connections").param("workflowId", graph.workflow())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        body.put("conditionExpr", "unit = 'kg'");
        call(post("/process-connections"), body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conditionExpr").value("unit = 'kg'"));
    }

    @Test
    void anInvalidConnectionUpdatePreservesTheConditionLabelAndVersion() throws Exception {
        Graph graph = graph();
        String connectionId = data(call(post("/process-connections"), connection(graph))).path("connectionId").asText();
        call(put("/process-connections/" + connectionId), Map.of("conditionExpr", "quantity == 3", "connectionLabel", "Rejected"))
            .andExpect(status().isBadRequest());
        call(get("/process-connections/" + connectionId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conditionExpr").value("quantity > 0"))
            .andExpect(jsonPath("$.data.connectionLabel").value("Original"))
            .andExpect(jsonPath("$.data.version").value(1));
        call(put("/process-connections/" + connectionId), Map.of("conditionExpr", "quantity = 3", "connectionLabel", "Updated"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void legacyDoubleEqualsRulesAreReportedAndBlockPublicationUntilCorrected() throws Exception {
        Graph graph = graph();
        String portId = data(call(post("/process-ios"), port(graph.source(), "output"))).path("processIoId").asText();
        String connectionId = data(call(post("/process-connections"), connection(graph))).path("connectionId").asText();
        jdbc.update("UPDATE process_io SET validation_rule = ? WHERE process_io_id = ?", "quantity == 1", portId);
        jdbc.update("UPDATE process_connection SET condition_expr = ? WHERE connection_id = ?", "quantity == 1", connectionId);
        call(get("/workflows/" + graph.workflow() + "/validation")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.errors").value(2))
            .andExpect(jsonPath("$.data.issues[?(@.code == 'EXPRESSION_INVALID')]", hasSize(2)));
        call(post("/workflows/" + graph.workflow() + "/revisions")).andExpect(status().isConflict());
        call(get("/workflows/" + graph.workflow() + "/revisions")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        call(put("/process-ios/" + portId), Map.of("validationRule", "quantity = 1")).andExpect(status().isOk());
        call(put("/process-connections/" + connectionId), Map.of("conditionExpr", "quantity = 1")).andExpect(status().isOk());
        call(post("/workflows/" + graph.workflow() + "/revisions")).andExpect(status().isOk());
    }

    private Graph graph() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Condition operators " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        return new Graph(workflow, source, target);
    }

    private Map<String, Object> port(String process, String direction) {
        return new LinkedHashMap<>(Map.of("processId", process, "itemId", "itm_demo_mix_output",
            "ioName", "Original", "direction", direction, "quantity", 1, "unit", "kg"));
    }

    private Map<String, Object> connection(Graph graph) {
        return new LinkedHashMap<>(Map.of("workflowId", graph.workflow(), "fromProcessId", graph.source(),
            "toProcessId", graph.target(), "connectionLabel", "Original", "conditionExpr", "quantity > 0"));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private record Graph(String workflow, String source, String target) {}
}
