package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProcessConnectionDecimalValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ProcessConnectionRepository connectionRepository;

    @ParameterizedTest
    @CsvSource({
        "flowRate, 1.0000000000000000001",
        "delayTimeSec, 1.0000000000000000001",
        "lossRate, 1.0000000000000000001",
        "flowRate, 1e-400",
        "delayTimeSec, 1e-400",
        "lossRate, 1e-400",
        "flowRate, 1e9999",
        "delayTimeSec, 1e9999",
        "lossRate, 1e9999",
        "flowRate, 1e2147483648",
        "delayTimeSec, 1e2147483648",
        "lossRate, 1e2147483648",
        "flowRate, 1e-2147483648",
        "delayTimeSec, 1e-2147483648",
        "lossRate, 1e-2147483648"
    })
    void rejectsTheOriginalOutOfRangeDecimalWithoutChangingTheConnection(String field, String number)
        throws Exception {
        String connectionId = createConnection();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + field + "\":" + number + "}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));

        mockMvc.perform(auth(get("/process-connections/" + connectionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate").value(1.25))
            .andExpect(jsonPath("$.data.delayTimeSec").value(0.25))
            .andExpect(jsonPath("$.data.lossRate").value(0.0001))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    @ParameterizedTest
    @CsvSource({
        "flowRate, 9999999999.9999",
        "delayTimeSec, 99999999.99",
        // A loss rate is a fraction below 1 (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 2).
        "lossRate, 0.9999"
    })
    void acceptsAndStoresTheExactDecimalBoundary(String field, String number) throws Exception {
        String connectionId = createConnection();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + field + "\":" + number + "}")))
            .andExpect(status().isOk());

        ProcessConnection saved = connectionRepository.findById(connectionId).orElseThrow();
        BigDecimal actual = switch (field) {
            case "flowRate" -> saved.getFlowRate();
            case "delayTimeSec" -> saved.getDelayTimeSec();
            default -> saved.getLossRate();
        };
        assertEquals(0, new BigDecimal(number).compareTo(actual));
    }

    @Test
    void keepsScientificNotationOmissionAndExplicitNullSemantics() throws Exception {
        String connectionId = createConnection();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowRate\":1e-4,\"delayTimeSec\":1.23000000000000000000}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate").value(0.0001))
            .andExpect(jsonPath("$.data.delayTimeSec").value(1.23))
            .andExpect(jsonPath("$.data.lossRate").value(0.0001));

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowRate\":null,\"delayTimeSec\":null,\"lossRate\":null}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate", nullValue()))
            .andExpect(jsonPath("$.data.delayTimeSec").value(0))
            .andExpect(jsonPath("$.data.lossRate").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"flowRate\":}", "{\"flowRate\":NaN}", "{\"flowRate\":true"})
    void malformedUpdatesReturnBadRequestWithoutChangingTheConnection(String body) throws Exception {
        String connectionId = createConnection();
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(auth(get("/process-connections/" + connectionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate").value(1.25))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void creationRejectsAnUnrepresentableNumberWithoutInsertingAConnection() throws Exception {
        String connectionId = createConnection();
        JsonNode existing = data(get("/process-connections/" + connectionId));
        String body = "{\"workflowId\":\"" + existing.path("workflowId").asText()
            + "\",\"fromProcessId\":\"" + existing.path("fromProcessId").asText()
            + "\",\"toProcessId\":\"" + existing.path("toProcessId").asText()
            + "\",\"flowRate\":1e-2147483648}";

        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("flowRate")));
        mockMvc.perform(auth(get("/process-connections").param("workflowId", existing.path("workflowId").asText())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    private String createConnection() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Decimal validation " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target, "flowRate", new BigDecimal("1.25"),
                "delayTimeSec", new BigDecimal("0.25"), "lossRate", new BigDecimal("0.0001")))))
            .path("connectionId").asText();
    }

    private String createProcess(String workflowId, String name) throws Exception {
        return data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId, "processName", name))))
            .path("processId").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
