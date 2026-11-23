package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProcessPortConnectionTextValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    static Stream<Arguments> invalidPortText() {
        return Stream.of(
            Arguments.of("ioName", "bad\u0000name"),
            Arguments.of("ioType", "bad\ud800type"),
            Arguments.of("role", "product\u0000"),
            Arguments.of("resourceType", "bad\udc00resource"),
            Arguments.of("unit", "bad\u0000unit"),
            Arguments.of("formula", "bad\ud800formula"),
            Arguments.of("colorScheme", "bad\udc00color"),
            Arguments.of("validationRule", "unit = 'bad\u0000unit'"),
            Arguments.of("direction", "output\u0000"),
            Arguments.of("requiredYn", "Y\u0000"),
            Arguments.of("allowShortageYn", "N\u0000")
        );
    }

    static Stream<Arguments> invalidConnectionText() {
        return Stream.of(
            Arguments.of("sourceHandle", "bad\u0000source"),
            Arguments.of("targetHandle", "bad\ud800target"),
            Arguments.of("connectionType", "bad\udc00type"),
            Arguments.of("connectionLabel", "bad\u0000label"),
            Arguments.of("unit", "kg\u0000"),
            Arguments.of("conditionExpr", "unit = 'bad\ud800unit'"),
            Arguments.of("failurePolicy", "stop\u0000")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidPortText")
    void rejectsUnstorablePortTextOnCreation(String field, String value) throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = portBody(fixture.source());
        body.put(field, value);

        call(post("/process-ios"), body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-ios").param("processId", fixture.source()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @MethodSource("invalidPortText")
    void rejectsUnstorablePortTextWithoutChangingExistingValues(String field, String value) throws Exception {
        Fixture fixture = fixture();
        String port = data(call(post("/process-ios"), portBody(fixture.source()))).path("processIoId").asText();

        call(put("/process-ios/" + port), Map.of(field, value, "quantity", 2))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-ios/" + port))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ioName").value("Original port"))
            .andExpect(jsonPath("$.data.quantity").value(1))
            .andExpect(jsonPath("$.data.role").value("product"));
    }

    @ParameterizedTest
    @MethodSource("invalidConnectionText")
    void rejectsUnstorableConnectionTextOnCreation(String field, String value) throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = connectionBody(fixture);
        body.put(field, value);

        call(post("/process-connections"), body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-connections").param("workflowId", fixture.workflow()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @MethodSource("invalidConnectionText")
    void rejectsUnstorableConnectionTextWithoutChangingExistingValues(String field, String value) throws Exception {
        Fixture fixture = fixture();
        String connection = data(call(post("/process-connections"), connectionBody(fixture)))
            .path("connectionId").asText();

        call(put("/process-connections/" + connection), Map.of(field, value, "priority", 2))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/process-connections/" + connection))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.connectionLabel").value("Original connection"))
            .andExpect(jsonPath("$.data.priority").value(0))
            .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void validUnicodeAndSupportedControlCharactersRemainUsable() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> portBody = portBody(fixture.source());
        portBody.put("ioName", "원료 😀\t포트");
        portBody.put("role", "제품 é");
        String port = data(call(post("/process-ios"), portBody)).path("processIoId").asText();
        call(get("/process-ios/" + port))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ioName").value("원료 😀\t포트"))
            .andExpect(jsonPath("$.data.role").value("제품 é"));
        call(put("/process-ios/" + port), Map.of("ioName", "수정 😀 포트"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ioName").value("수정 😀 포트"));

        Map<String, Object> connectionBody = connectionBody(fixture);
        connectionBody.put("connectionLabel", "운반 😀\n연결");
        String connection = data(call(post("/process-connections"), connectionBody)).path("connectionId").asText();
        call(get("/process-connections/" + connection))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.connectionLabel").value("운반 😀\n연결"));
        call(put("/process-connections/" + connection), Map.of("connectionLabel", "수정 😀 연결"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.connectionLabel").value("수정 😀 연결"));
    }

    private Fixture fixture() throws Exception {
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Text validation " + UUID.randomUUID()))).path("workflowId").asText();
        String source = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Source")))
            .path("processId").asText();
        String target = data(call(post("/processes"), Map.of("workflowId", workflow, "processName", "Target")))
            .path("processId").asText();
        return new Fixture(workflow, source, target);
    }

    private Map<String, Object> portBody(String process) {
        return new LinkedHashMap<>(Map.of("processId", process, "itemId", "itm_demo_mix_output",
            "ioName", "Original port", "direction", "output", "quantity", 1, "unit", "kg", "role", "product"));
    }

    private Map<String, Object> connectionBody(Fixture fixture) {
        return new LinkedHashMap<>(Map.of("workflowId", fixture.workflow(), "fromProcessId", fixture.source(),
            "toProcessId", fixture.target(), "connectionLabel", "Original connection"));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }

    private record Fixture(String workflow, String source, String target) {}
}
