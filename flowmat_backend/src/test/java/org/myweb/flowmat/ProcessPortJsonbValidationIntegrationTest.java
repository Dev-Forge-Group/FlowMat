package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProcessPortJsonbValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    static String[] unstorableSchemas() {
        return new String[] {
            "{\"type\":\"object\",\"description\":\"bad\\u0000text\"}",
            "{\"type\":\"object\",\"x-\\u0000\":\"metadata\"}",
            "{\"type\":\"object\",\"examples\":[{\"label\":\"bad\\ud800text\"}]}",
            "{\"type\":\"object\",\"properties\":{\"batch\":{\"type\":\"string\",\"description\":\"bad\\udc00text\"}}}",
            "{\"type\":\"object\",\"x-extra\":{\"labels\":[\"safe\",\"bad\\u0000text\"]}}"
        };
    }

    @ParameterizedTest
    @MethodSource("unstorableSchemas")
    void creationRejectsUnstorableCharactersThroughoutTheSchema(String schema) throws Exception {
        String process = process();

        call(post("/process-ios"), portBody(process, schema))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("schemaJson")));

        call(get("/process-ios").param("processId", process))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @MethodSource("unstorableSchemas")
    void updateRejectsUnstorableCharactersWithoutChangingThePort(String schema) throws Exception {
        String process = process();
        String port = data(call(post("/process-ios"), portBody(process,
            "{\"type\":\"object\",\"description\":\"original\"}"))).path("processIoId").asText();

        call(put("/process-ios/" + port), "{\"role\":\"changed\",\"schemaJson\":" + schema + "}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("schemaJson")));

        call(get("/process-ios/" + port))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("product"))
            .andExpect(jsonPath("$.data.schemaJson.description").value("original"));
    }

    @Test
    void supportedUnicodeAndUnknownSchemaMetadataRoundTrip() throws Exception {
        String process = process();
        String schema = "{\"type\":\"object\",\"description\":\"배치 \\ud83d\\ude00\","
            + "\"x-extra\":{\"labels\":[\"é\",\"allowed\\u0001control\"]}}";
        String port = data(call(post("/process-ios"), portBody(process, schema))).path("processIoId").asText();

        call(get("/process-ios/" + port))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.schemaJson.description").value("배치 😀"))
            .andExpect(jsonPath("$.data.schemaJson.x-extra.labels[0]").value("é"))
            .andExpect(jsonPath("$.data.schemaJson.x-extra.labels[1]").value("allowed\u0001control"));
        call(put("/process-ios/" + port), "{\"schemaJson\":" + schema + "}").andExpect(status().isOk());
    }

    private String process() throws Exception {
        String workflow = data(call(post("/workflows"), objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Port JSONB " + UUID.randomUUID())))).path("workflowId").asText();
        return data(call(post("/processes"), objectMapper.writeValueAsString(Map.of("workflowId", workflow,
            "processName", "Source")))).path("processId").asText();
    }

    private String portBody(String process, String schema) {
        return "{\"processId\":\"" + process + "\",\"itemId\":\"itm_demo_mix_output\",\"direction\":\"output\","
            + "\"quantity\":1,\"unit\":\"kg\",\"role\":\"product\",\"schemaJson\":" + schema + "}";
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
