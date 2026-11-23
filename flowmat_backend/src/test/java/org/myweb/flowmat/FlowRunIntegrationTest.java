package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class FlowRunIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void aFlowRunKeepsItsPublishedRevisionAndImmutableInputs() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"General run "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();

        JsonNode started = data(post("/flow-runs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                + revisionId + "\",\"runType\":\"test\",\"inputPayload\":{\"batch\":7}}"));
        String runId = started.path("flowRunId").asText();
        org.junit.jupiter.api.Assertions.assertEquals("running", started.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("demo-owner", started.path("requestedBy").asText());
        mockMvc.perform(auth(get("/flow-runs/" + runId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workflowRevisionId").value(revisionId))
            .andExpect(jsonPath("$.data.inputPayload.batch").value(7));
        mockMvc.perform(auth(get("/flow-runs").param("workflowId", workflowId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].flowRunId").value(runId));
        mockMvc.perform(auth(post("/flow-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                    + revisionId + "\",\"runType\":\"unknown\"}")))
            .andExpect(status().isBadRequest());

        data(post("/workflows/" + workflowId + "/revisions/" + revisionId + "/retire"));
        mockMvc.perform(auth(get("/flow-runs/" + runId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workflowRevisionId").value(revisionId));
        mockMvc.perform(auth(post("/flow-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                    + revisionId + "\",\"runType\":\"test\"}")))
            .andExpect(status().isConflict());

        mockMvc.perform(auth(post("/flow-runs/" + runId + "/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outputPayload\":{\"accepted\":true}}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("finished"))
            .andExpect(jsonPath("$.data.outputPayload.accepted").value(true))
            .andExpect(jsonPath("$.data.endedAt").isNotEmpty());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
            .andExpect(status().isConflict());
        mockMvc.perform(get("/flow-runs/" + runId)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
    }

    @Test
    void productionRunWithPublishedRevisionHasOneLinkedFlowRunAndFinishesTogether() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Linked run "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String productionRunId = data(post("/production-runs/start")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + workflowId
                + "\",\"workflowRevisionId\":\"" + revisionId + "\",\"plannedOutputQty\":2}"))
            .path("productionRunId").asText();

        JsonNode linked = data(get("/flow-runs").param("workflowId", workflowId));
        org.junit.jupiter.api.Assertions.assertEquals(1, linked.size());
        org.junit.jupiter.api.Assertions.assertEquals(productionRunId, linked.get(0).path("productionRunId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(revisionId, linked.get(0).path("workflowRevisionId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("running", linked.get(0).path("status").asText());
        mockMvc.perform(auth(post("/flow-runs/" + linked.get(0).path("flowRunId").asText() + "/finish")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isConflict());

        data(post("/workflows/" + workflowId + "/revisions/" + revisionId + "/retire"));
        mockMvc.perform(auth(post("/production-runs/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + workflowId
                    + "\",\"workflowRevisionId\":\"" + revisionId + "\",\"plannedOutputQty\":2}")))
            .andExpect(status().isConflict());

        data(post("/production-runs/" + productionRunId + "/finish")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actualOutputQty\":1}"));
        mockMvc.perform(auth(get("/flow-runs/" + linked.get(0).path("flowRunId").asText())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("finished"))
            .andExpect(jsonPath("$.data.outputPayload.actualOutputQty").value(1));
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
