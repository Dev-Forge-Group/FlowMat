package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class RevisionPublishGateIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider tokens;

    @Test
    void errorsBlockPublicationButWarningsDoNot() throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Gate "
                + UUID.randomUUID() + "\"}")) .path("workflowId").asText();
        String source = process(workflow, "Source");
        String target = process(workflow, "Target");
        String output = port(source, "output");
        String input = port(target, "input");

        mockMvc.perform(auth(post("/workflows/" + workflow + "/revisions")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Workflow has 1 error")));
        data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflow + "\",\"fromProcessId\":\"" + source
                + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                + "\",\"toIoId\":\"" + input + "\"}"));
        mockMvc.perform(auth(post("/workflows/" + workflow + "/revisions")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revisionNo").value(1));
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
        return mapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + tokens.generateAccessToken(DEMO_OWNER));
    }
}
