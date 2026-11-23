package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
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
class WorkflowBlankTextIntegrationTest extends IntegrationTestSupport {

    private static final String TRIMMED_EMPTY = " \u0001\u001f ";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void creatingAPortUsesDefaultsAndNullsAfterTrimmingEmptyValues() throws Exception {
        Graph graph = graph();
        Map<String, Object> request = port(graph.source(), "output");
        for (String field : new String[] {"ioName", "ioType", "resourceType", "colorScheme", "role",
            "formula", "validationRule"}) request.put(field, TRIMMED_EMPTY);
        String id = data(call(post("/process-ios"), request)).path("processIoId").asText();
        JsonNode stored = data(call(get("/process-ios/" + id)));
        assertThat(stored.path("ioType").asText()).isEqualTo("material");
        assertThat(stored.path("resourceType").asText()).isEqualTo("material");
        assertThat(stored.path("colorScheme").asText()).isEqualTo("emerald");
        assertNullFields(stored, "ioName", "role", "formula", "validationRule");
    }

    @Test
    void emptyPortUpdatesClearOptionalTextAndPreserveConnectedCodesAndUnit() throws Exception {
        Graph graph = graph();
        Map<String, Object> request = port(graph.source(), "output");
        request.putAll(Map.of("ioName", "Output", "role", "product", "formula", "1", "validationRule", "quantity >= 0"));
        String source = data(call(post("/process-ios"), request)).path("processIoId").asText();
        String target = data(call(post("/process-ios"), port(graph.target(), "input"))).path("processIoId").asText();
        Map<String, Object> connectionRequest = connection(graph);
        connectionRequest.putAll(Map.of("fromIoId", source, "toIoId", target));
        String connection = data(call(post("/process-connections"), connectionRequest)).path("connectionId").asText();
        call(put("/process-ios/" + source), Map.of("ioName", TRIMMED_EMPTY, "role", TRIMMED_EMPTY,
            "formula", TRIMMED_EMPTY, "validationRule", TRIMMED_EMPTY, "ioType", TRIMMED_EMPTY,
            "resourceType", TRIMMED_EMPTY, "unit", TRIMMED_EMPTY)).andExpect(status().isOk());
        JsonNode stored = data(call(get("/process-ios/" + source)));
        assertThat(stored.path("ioType").asText()).isEqualTo("material");
        assertThat(stored.path("resourceType").asText()).isEqualTo("material");
        assertThat(stored.path("unit").asText()).isEqualTo("kg");
        assertNullFields(stored, "ioName", "role", "formula", "validationRule");
        call(get("/process-connections/" + connection)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fromIoId").value(source)).andExpect(jsonPath("$.data.toIoId").value(target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0001", " \u001f "})
    void aRequiredPortUnitCannotBecomeEmptyAfterTrimming(String value) throws Exception {
        Graph graph = graph();
        Map<String, Object> request = port(graph.source(), "output");
        request.put("unit", value);
        call(post("/process-ios"), request).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unit")));
        assertThat(data(call(get("/process-ios").param("processId", graph.source()))).size()).isZero();
        request.put("unit", " kg ");
        call(post("/process-ios"), request).andExpect(status().isOk()).andExpect(jsonPath("$.data.unit").value("kg"));
    }

    @Test
    void creatingAConnectionUsesDefaultsAndClearsOptionalTextAfterTrimming() throws Exception {
        Graph graph = graph();
        Map<String, Object> request = connection(graph);
        for (String field : new String[] {"connectionType", "connectionLabel", "unit", "conditionExpr",
            "failurePolicy", "sourceHandle", "targetHandle"}) request.put(field, TRIMMED_EMPTY);
        String id = data(call(post("/process-connections"), request)).path("connectionId").asText();
        JsonNode stored = data(call(get("/process-connections/" + id)));
        assertThat(stored.path("connectionType").asText()).isEqualTo("material");
        assertThat(stored.path("failurePolicy").asText()).isEqualTo("stop");
        assertThat(stored.path("sourceHandle").asText()).isEqualTo("out-default");
        assertThat(stored.path("targetHandle").asText()).isEqualTo("in-default");
        assertNullFields(stored, "connectionLabel", "unit", "conditionExpr");
    }

    @Test
    void emptyConnectionUpdatesClearOptionalTextAndPreserveTheConnectionType() throws Exception {
        Graph graph = graph();
        Map<String, Object> request = connection(graph);
        request.putAll(Map.of("connectionType", "information", "connectionLabel", "Route", "unit", "kg",
            "conditionExpr", "quantity >= 0", "sourceHandle", "custom-out", "targetHandle", "custom-in"));
        String id = data(call(post("/process-connections"), request)).path("connectionId").asText();
        call(put("/process-connections/" + id), Map.of("connectionType", TRIMMED_EMPTY,
            "connectionLabel", TRIMMED_EMPTY, "unit", TRIMMED_EMPTY, "conditionExpr", TRIMMED_EMPTY,
            "sourceHandle", TRIMMED_EMPTY, "targetHandle", TRIMMED_EMPTY)).andExpect(status().isOk());
        JsonNode stored = data(call(get("/process-connections/" + id)));
        assertThat(stored.path("connectionType").asText()).isEqualTo("information");
        assertThat(stored.path("sourceHandle").asText()).isEqualTo("out-default");
        assertThat(stored.path("targetHandle").asText()).isEqualTo("in-default");
        assertNullFields(stored, "connectionLabel", "unit", "conditionExpr");
    }

    private void assertNullFields(JsonNode value, String... fields) {
        for (String field : fields) assertThat(value.has(field) && value.get(field).isNull()).as(field).isTrue();
    }

    private Map<String, Object> port(String process, String direction) {
        return new LinkedHashMap<>(Map.of("processId", process, "itemId", "itm_demo_mix_output",
            "direction", direction, "quantity", 1, "unit", "kg"));
    }

    private Map<String, Object> connection(Graph graph) {
        return new LinkedHashMap<>(Map.of("workflowId", graph.workflow(), "fromProcessId", graph.source(),
            "toProcessId", graph.target()));
    }

    private Graph graph() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Blank text " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        return new Graph(workflow, source, target);
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

    private record Graph(String workflow, String source, String target) {}
}
