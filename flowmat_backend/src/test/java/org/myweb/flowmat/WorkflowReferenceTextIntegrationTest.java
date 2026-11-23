package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
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
class WorkflowReferenceTextIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest
    @ValueSource(strings = {"processId", "itemId"})
    void anInvalidReferenceCannotCreateAPort(String field) throws Exception {
        Graph graph = graph();
        Map<String, Object> body = port(graph.source(), "output");
        body.put(field, body.get(field) + "\u0000");
        call(post("/process-ios"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-ios").param("processId", graph.source())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        body.put(field, "processId".equals(field) ? graph.source() : "itm_demo_mix_output");
        call(post("/process-ios"), body).andExpect(status().isOk());
    }

    @Test
    void anUnpairedSurrogateReferenceIsRejectedBeforeTheDatabaseCanReplaceIt() throws Exception {
        Graph graph = graph();
        Map<String, Object> body = port(graph.source(), "output");
        body.put("itemId", "itm_demo_mix_output\ud800");
        call(post("/process-ios"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("itemId")));
        call(get("/process-ios").param("processId", graph.source())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void anInvalidReplacementItemPreservesThePortAndItsConnection() throws Exception {
        LinkedGraph graph = linkedGraph();
        call(put("/process-ios/" + graph.sourcePort()), Map.of("itemId", "itm_demo_mix_output\u0000",
            "ioName", "Rejected replacement")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("itemId")));
        call(get("/process-ios/" + graph.sourcePort())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.itemId").value("itm_demo_mix_output"))
            .andExpect(jsonPath("$.data.ioName").value("Original"));
        assertConnection(graph);
        call(put("/process-ios/" + graph.sourcePort()), Map.of("itemId", "itm_demo_mix_output", "ioName", "Renamed"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ioName").value("Renamed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflowId", "fromProcessId", "toProcessId", "fromIoId", "toIoId", "itemId"})
    void anInvalidReferenceCannotCreateAConnection(String field) throws Exception {
        Graph graph = graph();
        String source = data(call(post("/process-ios"), port(graph.source(), "output"))).path("processIoId").asText();
        String target = data(call(post("/process-ios"), port(graph.target(), "input"))).path("processIoId").asText();
        Map<String, Object> body = connection(graph, source, target);
        Object original = body.get(field);
        body.put(field, original + "\u0000");
        call(post("/process-connections"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-connections").param("workflowId", graph.workflow())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
        body.put(field, original);
        call(post("/process-connections"), body).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fromIoId", "toIoId", "itemId"})
    void anInvalidReferenceUpdatePreservesTheConnectionAndItsVersion(String field) throws Exception {
        LinkedGraph graph = linkedGraph();
        String reference = switch (field) {
            case "fromIoId" -> graph.sourcePort();
            case "toIoId" -> graph.targetPort();
            default -> "itm_demo_mix_output";
        };
        call(put("/process-connections/" + graph.connection()), Map.of(field, reference + "\u0000",
            "connectionLabel", "Rejected replacement")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        assertConnection(graph);
        call(put("/process-connections/" + graph.connection()), Map.of(field, reference, "connectionLabel", "Renamed"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.connectionLabel").value("Renamed"))
            .andExpect(jsonPath("$.data.version").value(2));
    }

    private void assertConnection(LinkedGraph graph) throws Exception {
        call(get("/process-connections/" + graph.connection())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fromIoId").value(graph.sourcePort()))
            .andExpect(jsonPath("$.data.toIoId").value(graph.targetPort()))
            .andExpect(jsonPath("$.data.itemId").value("itm_demo_mix_output"))
            .andExpect(jsonPath("$.data.connectionLabel").value("Original"))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    private LinkedGraph linkedGraph() throws Exception {
        Graph graph = graph();
        String source = data(call(post("/process-ios"), port(graph.source(), "output"))).path("processIoId").asText();
        String target = data(call(post("/process-ios"), port(graph.target(), "input"))).path("processIoId").asText();
        String connection = data(call(post("/process-connections"), connection(graph, source, target)))
            .path("connectionId").asText();
        return new LinkedGraph(source, target, connection);
    }

    private Map<String, Object> port(String process, String direction) {
        return new LinkedHashMap<>(Map.of("processId", process, "itemId", "itm_demo_mix_output", "ioName", "Original",
            "direction", direction, "quantity", 1, "unit", "kg"));
    }

    private Map<String, Object> connection(Graph graph, String source, String target) {
        return new LinkedHashMap<>(Map.of("workflowId", graph.workflow(), "fromProcessId", graph.source(),
            "toProcessId", graph.target(), "fromIoId", source, "toIoId", target,
            "itemId", "itm_demo_mix_output", "connectionLabel", "Original"));
    }

    private Graph graph() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Reference validation " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        return new Graph(workflow, source, target);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }

    private record Graph(String workflow, String source, String target) {}
    private record LinkedGraph(String sourcePort, String targetPort, String connection) {}
}
