package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@ResourceLock("java.util.Locale")
class ProcessPortCodeLocaleIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void normalizesPortCodesAndColorsWhenCreatingInATurkishLocale() throws Exception {
        Graph graph = graph();
        withTurkishLocale(() -> call(post("/process-ios"), Map.of("processId", graph.target(),
            "itemId", "itm_demo_mix_output", "direction", " INPUT ", "quantity", 1, "unit", "kg",
            "ioType", " MATERIAL ", "resourceType", " MATERIAL ", "colorScheme", " INDIGO "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.direction").value("input"))
            .andExpect(jsonPath("$.data.ioType").value("material"))
            .andExpect(jsonPath("$.data.resourceType").value("material"))
            .andExpect(jsonPath("$.data.colorScheme").value("indigo")));
    }

    @Test
    void aPortCodeCaseChangeDoesNotBreakAnExistingConnection() throws Exception {
        Graph graph = graph();
        String sourcePort = port(graph.source(), "output");
        String targetPort = port(graph.target(), "input");
        data(call(post("/process-connections"), connection(graph, sourcePort, targetPort, "material")));
        withTurkishLocale(() -> call(put("/process-ios/" + sourcePort), Map.of("ioType", " MATERIAL ",
            "resourceType", " MATERIAL ", "colorScheme", " INDIGO "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ioType").value("material"))
            .andExpect(jsonPath("$.data.resourceType").value("material"))
            .andExpect(jsonPath("$.data.colorScheme").value("indigo")));
    }

    @Test
    void connectionCreationAndUpdateUseTheSameLocaleIndependentCode() throws Exception {
        Graph graph = graph();
        String sourcePort = port(graph.source(), "output");
        String targetPort = port(graph.target(), "input");
        withTurkishLocale(() -> {
            String connection = data(call(post("/process-connections"),
                connection(graph, sourcePort, targetPort, " MATERIAL "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connectionType").value("material")))
                .path("connectionId").asText();
            call(put("/process-connections/" + connection), Map.of("connectionType", " MATERIAL "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.connectionType").value("material"));
        });
    }

    private Map<String, Object> connection(Graph graph, String sourcePort, String targetPort, String type) {
        return Map.of("workflowId", graph.workflow(), "fromProcessId", graph.source(), "toProcessId", graph.target(),
            "fromIoId", sourcePort, "toIoId", targetPort, "connectionType", type);
    }

    private String port(String process, String direction) throws Exception {
        return data(call(post("/process-ios"), Map.of("processId", process, "itemId", "itm_demo_mix_output",
            "direction", direction, "quantity", 1, "unit", "kg", "ioType", "material", "resourceType", "material")))
            .path("processIoId").asText();
    }

    private Graph graph() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Locale graph " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        return new Graph(workflow, source, target);
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
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }

    private record Graph(String workflow, String source, String target) {}
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
