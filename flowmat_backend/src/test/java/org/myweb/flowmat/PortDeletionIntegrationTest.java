package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class PortDeletionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void deletingPortAlsoDeletesItsLiveConnections() throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port delete " + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String source = process(workflow, "Source");
        String target = process(workflow, "Target");
        String output = port(source, "output");
        String input = port(target, "input");
        String connection = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflow + "\",\"fromProcessId\":\"" + source
                + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                + "\",\"toIoId\":\"" + input + "\"}"))
            .path("connectionId").asText();

        mockMvc.perform(auth(delete("/process-ios/" + output))).andExpect(status().isOk());
        mockMvc.perform(auth(get("/process-connections/" + connection))).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/process-connections").param("workflowId", workflow)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void changingLinkedPortDirectionOrSchemaCannotBreakConnection() throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port change " + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String source = process(workflow, "Source");
        String target = process(workflow, "Target");
        String output = port(source, "output");
        String input = port(target, "input");
        data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflow + "\",\"fromProcessId\":\"" + source
                + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                + "\",\"toIoId\":\"" + input + "\"}"));

        mockMvc.perform(auth(put("/process-ios/" + output).contentType(MediaType.APPLICATION_JSON)
                .content("{\"direction\":\"input\"}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Port change would break connection")));
        mockMvc.perform(auth(get("/process-ios/" + output)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.direction").value("output"));

        data(put("/process-ios/" + input).contentType(MediaType.APPLICATION_JSON)
            .content("{\"schemaJson\":{\"type\":\"object\",\"properties\":{"
                + "\"batch\":{\"type\":\"string\"}},\"required\":[\"batch\"]}}"));
        mockMvc.perform(auth(put("/process-ios/" + input).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unit\":\"ea\"}")))
            .andExpect(status().isConflict());
    }

    private String process(String workflow, String name) throws Exception {
        return data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflow + "\",\"processName\":\"" + name + "\"}"))
            .path("processId").asText();
    }

    private String port(String process, String direction) throws Exception {
        return data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + process + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"" + direction + "\",\"quantity\":1,\"unit\":\"kg\"}"))
            .path("processIoId").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String json = mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
