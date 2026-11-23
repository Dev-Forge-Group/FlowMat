package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ProductionRunRevisionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runsKeepTheirPublishedRevisionAndRetiredRevisionsCannotStartRuns() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Run revision "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String firstRevisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String secondRevisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();

        JsonNode explicitRun = data(start(workflowId, firstRevisionId));
        String explicitRunId = explicitRun.path("productionRunId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(firstRevisionId,
            explicitRun.path("workflowRevisionId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(secondRevisionId,
            data(start(workflowId, null)).path("workflowRevisionId").asText());

        data(post("/workflows/" + workflowId + "/revisions/" + firstRevisionId + "/retire"));
        mockMvc.perform(auth(start(workflowId, firstRevisionId)))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(start(DEMO_WORKFLOW, secondRevisionId)))
            .andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/production-runs/" + explicitRunId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workflowRevisionId").value(firstRevisionId));
    }

    @Test
    void runItemsUsePublishedProcessesAfterDraftDeletionAndRejectNewDraftNodes() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Frozen process "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String processId = createProcess(workflowId, "Published process");
        String processIoId = data(post("/process-ios")
            .contentType(MediaType.APPLICATION_JSON)
            // Not required: the port has no connection, and publishing refuses an unconnected required input
            // (docs/domain/process-port-connection-contract.md F6); what this test checks is the frozen process.
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"input\",\"quantity\":1,\"unit\":\"kg\",\"requiredYn\":\"N\"}"))
            .path("processIoId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = data(start(workflowId, revisionId)).path("productionRunId").asText();

        mockMvc.perform(auth(delete("/process-ios/" + processIoId))).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/processes/" + processId))).andExpect(status().isOk());
        String draftOnlyProcessId = createProcess(workflowId, "Unpublished process");

        mockMvc.perform(auth(record(runId, processId, processIoId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.processId").value(processId))
            .andExpect(jsonPath("$.data.processIoId").value(processIoId));
        mockMvc.perform(auth(record(runId, null, processIoId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.processId").value(processId));
        mockMvc.perform(auth(record(runId, processId, processIoId, "output")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(record(runId, draftOnlyProcessId, null)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void runItemsCanUseAPublishedPortThatHasADataSchema() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Schema port "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String processId = createProcess(workflowId, "Schema process");
        // The snapshot stores schemaJson as an object; recording must still read the frozen port.
        String processIoId = data(post("/process-ios")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"input\",\"quantity\":1,\"unit\":\"kg\",\"requiredYn\":\"N\","
                + "\"schemaJson\":{\"type\":\"object\",\"properties\":{\"lot\":{\"type\":\"string\"}}}}"))
            .path("processIoId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = data(start(workflowId, revisionId)).path("productionRunId").asText();

        mockMvc.perform(auth(record(runId, processId, processIoId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.processIoId").value(processIoId));
    }

    private String createProcess(String workflowId, String name) throws Exception {
        return data(post("/processes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"" + name + "\"}"))
            .path("processId").asText();
    }

    private MockHttpServletRequestBuilder record(String runId, String processId, String processIoId) {
        return record(runId, processId, processIoId, "input");
    }

    private MockHttpServletRequestBuilder record(String runId, String processId, String processIoId, String direction) {
        String optionalProcess = processId == null ? "" : "\"processId\":\"" + processId + "\",";
        String optionalIo = processIoId == null ? "" : ",\"processIoId\":\"" + processIoId + "\"";
        return post("/production-runs/" + runId + "/items")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{" + optionalProcess + "\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"" + direction + "\",\"plannedQty\":1,\"unit\":\"kg\"" + optionalIo + "}");
    }

    private MockHttpServletRequestBuilder start(String workflowId, String revisionId) {
        String optional = revisionId == null ? "" : ",\"workflowRevisionId\":\"" + revisionId + "\"";
        return post("/production-runs/start")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\"" + workflowId
                + "\",\"plannedOutputQty\":1" + optional + "}");
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
