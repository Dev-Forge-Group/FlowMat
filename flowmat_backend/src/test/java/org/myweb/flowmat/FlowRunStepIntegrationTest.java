package org.myweb.flowmat;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class FlowRunStepIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void plannedStepCanBeRescheduledAndCannotStartEarly() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Schedule "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String nodeId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Mix\"}"))
            .path("processId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = startRun(workflowId, revisionId);
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toString();
        String stepId = data(post("/flow-runs/" + runId + "/steps")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nodeId\":\"" + nodeId + "\",\"scheduledAt\":\"" + future + "\"}"))
            .path("stepId").asText();
        mockMvc.perform(auth(get("/flow-runs/" + runId + "/steps")))
            .andExpect(jsonPath("$.data[0].scheduledAt").isNotEmpty());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + stepId + "/start")))
            .andExpect(status().isConflict());
        mockMvc.perform(put("/flow-runs/" + runId + "/steps/" + stepId + "/schedule")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":null}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(auth(put("/flow-runs/" + runId + "/steps/" + stepId + "/schedule")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"invalid\"}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put("/flow-runs/" + runId + "/steps/" + stepId + "/schedule")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":null}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scheduledAt").value(org.hamcrest.Matchers.nullValue()));
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/start"));
        mockMvc.perform(auth(put("/flow-runs/" + runId + "/steps/" + stepId + "/schedule")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":null}")))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(get("/flow-runs/" + runId + "/events")))
            .andExpect(jsonPath("$.data[2].eventType").value("step_scheduled"))
            .andExpect(jsonPath("$.data[2].payload.scheduledAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void stepAttemptsAndEventsKeepARevisionPinnedHistory() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Step "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String nodeId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Mix\"}"))
            .path("processId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = data(post("/flow-runs").contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", "flow-step-contract-start")
            .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                + revisionId + "\",\"runType\":\"test\"}"))
            .path("flowRunId").asText();
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nodeId\":\"missing\"}")))
            .andExpect(status().isBadRequest());

        String stepId = data(post("/flow-runs/" + runId + "/steps")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nodeId\":\"" + nodeId + "\",\"inputSnapshot\":{\"kg\":5}}"))
            .path("stepId").asText();
        mockMvc.perform(get("/flow-runs/" + runId + "/steps")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/flow-runs/" + runId + "/steps/" + stepId + "/start")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/finish")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isConflict());
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/start"));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + stepId + "/start")))
            .andExpect(status().isConflict());
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/fail")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"errorCode\":\"MACHINE_STOP\",\"errorMessage\":\"Stopped\"}"));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/finish")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isConflict());
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/retry"));
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"kg\":4}}"));

        mockMvc.perform(auth(get("/flow-runs/" + runId + "/steps")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].nodeId").value(nodeId))
            .andExpect(jsonPath("$.data[0].status").value("completed"))
            .andExpect(jsonPath("$.data[0].inputSnapshot.kg").value(5))
            .andExpect(jsonPath("$.data[0].outputSnapshot.kg").value(4));
        mockMvc.perform(auth(get("/flow-runs/" + runId + "/steps/" + stepId + "/attempts")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].status").value("failed"))
            .andExpect(jsonPath("$.data[0].errorCode").value("MACHINE_STOP"))
            .andExpect(jsonPath("$.data[1].status").value("completed"));
        mockMvc.perform(auth(get("/flow-runs/" + runId + "/events")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(6))
            .andExpect(jsonPath("$.data[0].eventType").value("run_started"))
            .andExpect(jsonPath("$.data[0].requestId").value("flow-step-contract-start"))
            .andExpect(jsonPath("$.data[0].actorType").value("user"));
        data(post("/flow-runs/" + runId + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}"));
        mockMvc.perform(auth(get("/flow-runs/" + runId + "/events")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(jsonPath("$.data[6].eventType").value("run_finished"));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nodeId\":\"" + nodeId + "\"}")))
            .andExpect(status().isConflict());
    }

    @Test
    void cancellingOrFailingRunClosesOpenStepsAndAttempts() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Stop "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String nodeId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Mix\"}"))
            .path("processId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String cancelRunId = startRun(workflowId, revisionId);
        String runningStepId = addStep(cancelRunId, nodeId);
        String plannedStepId = addStep(cancelRunId, nodeId);
        data(post("/flow-runs/" + cancelRunId + "/steps/" + runningStepId + "/start"));

        mockMvc.perform(post("/flow-runs/" + cancelRunId + "/cancel")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Unauthorized stop\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(auth(post("/flow-runs/" + cancelRunId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Operator stopped\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("cancelled"))
            .andExpect(jsonPath("$.data.endedAt").isNotEmpty());
        mockMvc.perform(auth(get("/flow-runs/" + cancelRunId + "/steps")))
            .andExpect(jsonPath("$.data[0].status").value("cancelled"))
            .andExpect(jsonPath("$.data[1].status").value("cancelled"));
        mockMvc.perform(auth(get("/flow-runs/" + cancelRunId + "/steps/" + runningStepId + "/attempts")))
            .andExpect(jsonPath("$.data[0].status").value("cancelled"))
            .andExpect(jsonPath("$.data[0].endedAt").isNotEmpty());
        mockMvc.perform(auth(get("/flow-runs/" + cancelRunId + "/steps/" + plannedStepId + "/attempts")))
            .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(auth(get("/flow-runs/" + cancelRunId + "/events")))
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(jsonPath("$.data[?(@.eventType == 'run_cancelled')].payload.reason")
                .value(contains("Operator stopped")));
        mockMvc.perform(auth(post("/flow-runs/" + cancelRunId + "/steps/" + runningStepId + "/retry")))
            .andExpect(status().isConflict());
        mockMvc.perform(auth(post("/flow-runs/" + cancelRunId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Again\"}")))
            .andExpect(status().isConflict());

        String failRunId = startRun(workflowId, revisionId);
        String failedStepId = addStep(failRunId, nodeId);
        data(post("/flow-runs/" + failRunId + "/steps/" + failedStepId + "/start"));
        mockMvc.perform(auth(post("/flow-runs/" + failRunId + "/fail")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"errorCode\":\"MACHINE_STOP\",\"errorMessage\":\"Stopped\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("failed"));
        mockMvc.perform(auth(get("/flow-runs/" + failRunId + "/steps")))
            .andExpect(jsonPath("$.data[0].status").value("failed"))
            .andExpect(jsonPath("$.data[0].errorCode").value("MACHINE_STOP"));
        mockMvc.perform(auth(get("/flow-runs/" + failRunId + "/steps/" + failedStepId + "/attempts")))
            .andExpect(jsonPath("$.data[0].status").value("failed"))
            .andExpect(jsonPath("$.data[0].errorCode").value("MACHINE_STOP"));
        mockMvc.perform(auth(post("/flow-runs/" + failRunId + "/finish")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
            .andExpect(status().isConflict());
    }

    @Test
    void oversizedRequestIdDoesNotBreakEventPersistence() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Request ID "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = data(post("/flow-runs").contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", "x".repeat(101))
            .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                + revisionId + "\",\"runType\":\"test\"}"))
            .path("flowRunId").asText();

        String persistedRequestId = data(get("/flow-runs/" + runId + "/events"))
            .path(0).path("requestId").asText();
        org.junit.jupiter.api.Assertions.assertTrue(persistedRequestId.matches("[0-9a-f]{32}"));
    }

    private String startRun(String workflowId, String revisionId) throws Exception {
        return data(post("/flow-runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"workflowRevisionId\":\""
                + revisionId + "\",\"runType\":\"test\"}"))
            .path("flowRunId").asText();
    }

    private String addStep(String runId, String nodeId) throws Exception {
        return data(post("/flow-runs/" + runId + "/steps")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nodeId\":\"" + nodeId + "\"}"))
            .path("stepId").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
