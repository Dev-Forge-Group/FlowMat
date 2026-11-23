package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class ZeroQuantityIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @ParameterizedTest
    @ValueSource(strings = {"0e1000000", "0e-1000000"})
    void aZeroPortQuantityDoesNotDependOnItsExponent(String value) throws Exception {
        Graph graph = graph();
        BigDecimal zero = new BigDecimal(value);
        String port = data(call(post("/process-ios"), Map.of("processId", graph.source(),
            "itemId", "itm_demo_mix_output", "direction", "output", "quantity", zero, "unit", "kg"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(0.0)))
            .path("processIoId").asText();
        call(put("/process-ios/" + port), Map.of("quantity", zero))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.quantity").value(0.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0e1000000", "0e-1000000"})
    void aZeroConnectionCapacityDoesNotDependOnItsExponent(String value) throws Exception {
        Graph graph = graph();
        BigDecimal zero = new BigDecimal(value);
        String connection = data(call(post("/process-connections"), Map.of("workflowId", graph.workflow(),
            "fromProcessId", graph.source(), "toProcessId", graph.target(), "capacity", zero))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.capacity").value(0.0)))
            .path("connectionId").asText();
        call(put("/process-connections/" + connection), Map.of("capacity", zero))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.capacity").value(0.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0e1000000", "0e-1000000"})
    void zeroProductionQuantitiesAreStoredAsZeroInStartRecordingFinishAndCorrection(String value) throws Exception {
        Graph graph = graph();
        BigDecimal zero = new BigDecimal(value);
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", graph.workflow(), "plannedOutputQty", zero))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.plannedOutputQty").value(0.0)))
            .path("productionRunId").asText();
        call(post("/production-runs/" + run + "/items"), Map.of("itemId", "itm_demo_mix_output",
            "direction", "output", "plannedQty", zero, "actualQty", zero, "unit", "kg"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.plannedQty").value(0.0))
            .andExpect(jsonPath("$.data.actualQty").value(0.0));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", zero))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.actualOutputQty").value(0.0));
        String otherRun = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", graph.workflow(), "plannedOutputQty", 1))).path("productionRunId").asText();
        call(post("/production-runs/" + otherRun + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        String correction = data(call(post("/production-runs/" + otherRun + "/corrections"), Map.of("reason", "Zero output",
            "lines", List.of(Map.of("kind", "set_output_qty", "afterQty", zero))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].afterQty").value(0.0)))
            .path("productionRunCorrectionId").asText();
        call(post("/production-runs/" + otherRun + "/corrections/" + correction + "/approve"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("applied"));
    }

    private Graph graph() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Zero quantity " + UUID.randomUUID()))).path("workflowId").asText();
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
