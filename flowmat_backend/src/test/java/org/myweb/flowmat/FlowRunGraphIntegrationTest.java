package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class FlowRunGraphIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void publishedGraphRoutesOnConditionAndKeepsPinnedCapacity() throws Exception {
        GraphFixture fixture = graph("skip", "quantity >= 2", 3);
        String runId = startGraph(fixture);
        JsonNode roots = data(get("/flow-runs/" + runId + "/steps"));
        assertEquals(1, roots.size());
        String rootId = roots.get(0).path("stepId").asText();
        assertEquals(fixture.source(), roots.get(0).path("nodeId").asText());
        assertEquals("planned", roots.get(0).path("status").asText());
        assertEquals(true, roots.get(0).path("sourceStepId").isNull());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps")
            .contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"" + fixture.target() + "\"}")))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/flow-runs/" + runId + "/steps/" + rootId + "/start")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/preview")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}")))
            .andExpect(status().isConflict());

        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/start"));
        int eventCount = data(get("/flow-runs/" + runId + "/events")).size();
        mockMvc.perform(post("/flow-runs/" + runId + "/steps/" + rootId + "/preview")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}"))
            .andExpect(status().isForbidden());
        JsonNode blockedPreview = data(post("/flow-runs/" + runId + "/steps/" + rootId + "/preview")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":1}}"));
        assertEquals(1, blockedPreview.size());
        assertEquals(false, blockedPreview.get(0).path("willRoute").asBoolean());
        JsonNode routedPreview = data(post("/flow-runs/" + runId + "/steps/" + rootId + "/preview")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}"));
        assertEquals(fixture.connection(), routedPreview.get(0).path("connectionId").asText());
        assertEquals(true, routedPreview.get(0).path("willRoute").asBoolean());
        assertEquals(eventCount, data(get("/flow-runs/" + runId + "/events")).size());
        assertEquals("running", data(get("/flow-runs/" + runId + "/steps")).get(0).path("status").asText());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/preview")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":4}}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Connection " + fixture.connection() + " capacity exceeded."));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":4}}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Connection " + fixture.connection() + " capacity exceeded."));
        assertEquals("running", data(get("/flow-runs/" + runId + "/steps")).get(0).path("status").asText());
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":1}}"));
        assertEquals(1, data(get("/flow-runs/" + runId + "/steps")).size());
        JsonNode filteredEvents = data(get("/flow-runs/" + runId + "/events"));
        JsonNode filtered = filteredEvents.get(filteredEvents.size() - 1);
        assertEquals("connection_filtered", filtered.path("eventType").asText());
        assertEquals(fixture.connection(), filtered.path("payload").path("connectionId").asText());
        assertEquals("condition_false", filtered.path("payload").path("reason").asText());
        data(post("/flow-runs/" + runId + "/finish").contentType(MediaType.APPLICATION_JSON).content("{}"));

        String secondRun = startGraph(fixture);
        String secondRoot = data(get("/flow-runs/" + secondRun + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + secondRun + "/steps/" + secondRoot + "/start"));
        data(put("/process-connections/" + fixture.connection()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"conditionExpr\":\"quantity >= 99\",\"capacity\":99}"));
        data(post("/flow-runs/" + secondRun + "/steps/" + secondRoot + "/complete")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}"));
        JsonNode steps = data(get("/flow-runs/" + secondRun + "/steps"));
        assertEquals(2, steps.size());
        assertEquals(fixture.target(), steps.get(1).path("nodeId").asText());
        assertEquals(fixture.connection(), steps.get(1).path("sourceConnectionId").asText());
        assertEquals(secondRoot, steps.get(1).path("sourceStepId").asText());
        assertEquals(2, steps.get(1).path("inputSnapshot").path("quantity").asInt());
        mockMvc.perform(auth(post("/flow-runs/" + secondRun + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isConflict());
        String targetStep = steps.get(1).path("stepId").asText();
        data(post("/flow-runs/" + secondRun + "/steps/" + targetStep + "/start"));
        data(post("/flow-runs/" + secondRun + "/steps/" + targetStep + "/fail")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"errorCode\":\"MACHINE_STOP\",\"errorMessage\":\"Stopped\"}"));
        assertEquals("skipped", data(get("/flow-runs/" + secondRun + "/steps")).get(1).path("status").asText());
        assertEquals("finished", data(post("/flow-runs/" + secondRun + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).path("status").asText());
    }

    @Test
    void retryPolicyAllowsThreeRetriesThenFailsRun() throws Exception {
        GraphFixture fixture = graph("retry", null, null);
        String runId = startGraph(fixture);
        String rootId = data(get("/flow-runs/" + runId + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/start"));
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}"));
        String targetId = data(get("/flow-runs/" + runId + "/steps")).get(1).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + targetId + "/start"));
        for (int attempt = 1; attempt <= 4; attempt++) {
            JsonNode result = data(post("/flow-runs/" + runId + "/steps/" + targetId + "/fail")
                .contentType(MediaType.APPLICATION_JSON).content("{\"errorCode\":\"RETRY_ME\"}"));
            assertEquals(attempt < 4 ? "running" : "failed", result.path("status").asText());
        }
        assertEquals(4, data(get("/flow-runs/" + runId + "/steps/" + targetId + "/attempts")).size());
        assertEquals("failed", data(get("/flow-runs/" + runId)).path("status").asText());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + targetId + "/retry")))
            .andExpect(status().isConflict());
    }

    @Test
    void rootFailureStopsGraphRun() throws Exception {
        GraphFixture fixture = graph("stop", null, null);
        String runId = startGraph(fixture);
        String rootId = data(get("/flow-runs/" + runId + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/start"));
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/fail")
            .contentType(MediaType.APPLICATION_JSON).content("{\"errorCode\":\"ROOT_FAILURE\"}"));
        assertEquals("failed", data(get("/flow-runs/" + runId)).path("status").asText());
        assertEquals("failed", data(get("/flow-runs/" + runId + "/steps")).get(0).path("status").asText());
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isConflict());
    }

    @Test
    void convergingPathsRetainTheExactUpstreamStep() throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("projectId", DEMO_PROJECT, "workflowName", "Lineage " + UUID.randomUUID()))))
            .path("workflowId").asText();
        String source = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Source")))).path("processId").asText();
        String directTarget = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Target")))).path("processId").asText();
        String middle = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Middle")))).path("processId").asText();
        String directConnection = connect(workflow, source, directTarget);
        connect(workflow, source, middle);
        String middleConnection = connect(workflow, middle, directTarget);
        String revision = data(post("/workflows/" + workflow + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = startGraph(new GraphFixture(workflow, source, directTarget, directConnection, revision));
        String sourceStep = data(get("/flow-runs/" + runId + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + sourceStep + "/start"));
        data(post("/flow-runs/" + runId + "/steps/" + sourceStep + "/complete")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":2}}"));
        JsonNode routed = data(get("/flow-runs/" + runId + "/steps"));
        assertEquals(3, routed.size());
        String middleStep = "";
        String directStep = "";
        for (JsonNode step : routed) {
            if (middle.equals(step.path("nodeId").asText())) middleStep = step.path("stepId").asText();
            if (directTarget.equals(step.path("nodeId").asText())) {
                directStep = step.path("stepId").asText();
                assertEquals(sourceStep, step.path("sourceStepId").asText());
                assertEquals(directConnection, step.path("sourceConnectionId").asText());
            }
        }
        assertNotEquals("", middleStep);
        assertNotEquals("", directStep);
        data(post("/flow-runs/" + runId + "/steps/" + middleStep + "/start"));
        data(post("/flow-runs/" + runId + "/steps/" + middleStep + "/complete")
            .contentType(MediaType.APPLICATION_JSON).content("{\"outputSnapshot\":{\"quantity\":1}}"));
        JsonNode finalSteps = data(get("/flow-runs/" + runId + "/steps"));
        assertEquals(4, finalSteps.size());
        assertEquals(directTarget, finalSteps.get(3).path("nodeId").asText());
        assertEquals(middleStep, finalSteps.get(3).path("sourceStepId").asText());
        assertEquals(middleConnection, finalSteps.get(3).path("sourceConnectionId").asText());
        String otherRunId = startGraph(new GraphFixture(workflow, source, directTarget, directConnection, revision));
        String foreignStepId = data(get("/flow-runs/" + otherRunId + "/steps")).get(0).path("stepId").asText();
        String targetStepId = finalSteps.get(3).path("stepId").asText();
        JsonNode targetLineage = data(get("/flow-runs/" + runId + "/steps/" + targetStepId + "/lineage"));
        assertEquals(targetStepId, targetLineage.path("step").path("stepId").asText());
        assertEquals(2, targetLineage.path("ancestors").size());
        assertEquals(sourceStep, targetLineage.path("ancestors").get(0).path("stepId").asText());
        assertEquals(middleStep, targetLineage.path("ancestors").get(1).path("stepId").asText());
        assertEquals(0, targetLineage.path("descendants").size());
        JsonNode rootLineage = data(get("/flow-runs/" + runId + "/steps/" + sourceStep + "/lineage"));
        assertEquals(0, rootLineage.path("ancestors").size());
        assertEquals(3, rootLineage.path("descendants").size());
        assertEquals(directStep, rootLineage.path("descendants").get(0).path("stepId").asText());
        assertEquals(middleStep, rootLineage.path("descendants").get(1).path("stepId").asText());
        assertEquals(targetStepId, rootLineage.path("descendants").get(2).path("stepId").asText());
        mockMvc.perform(auth(get("/flow-runs/" + otherRunId + "/steps/" + targetStepId + "/lineage")))
            .andExpect(status().isNotFound());
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
            "UPDATE flow_run_step SET source_step_id = ? WHERE step_id = ?", foreignStepId, targetStepId));
    }

    @Test
    void publishedPortRulesRejectInvalidTransferWithoutEndingSourceStep() throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("projectId", DEMO_PROJECT, "workflowName", "Port rule " + UUID.randomUUID()))))
            .path("workflowId").asText();
        String source = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Source")))).path("processId").asText();
        String target = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Target")))).path("processId").asText();
        Map<String, Object> schema = Map.of("type", "object",
            "properties", Map.of("grade", Map.of("type", "string")), "required", new String[] { "grade" });
        String outputPort = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("processId", source, "itemId", "itm_demo_mix_output", "direction", "output",
                "quantity", 1, "unit", "kg", "schemaJson", schema, "validationRule", "quantity >= 2"))))
            .path("processIoId").asText();
        String inputPort = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("processId", target, "itemId", "itm_demo_mix_output", "direction", "input",
                "quantity", 1, "unit", "kg", "schemaJson", schema, "validationRule", "attrs.grade = 'A'"))))
            .path("processIoId").asText();
        data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "fromProcessId", source, "toProcessId", target,
                "fromIoId", outputPort, "toIoId", inputPort))));
        String revision = data(post("/workflows/" + workflow + "/revisions"))
            .path("workflowRevisionId").asText();
        String runId = startGraph(new GraphFixture(workflow, source, target, "unused", revision));
        String rootId = data(get("/flow-runs/" + runId + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/start"));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":1,\"attrs\":{\"grade\":\"A\"}}}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Port " + outputPort + " validationRule did not pass."));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":2,\"attrs\":{\"grade\":\"B\"}}}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Port " + inputPort + " validationRule did not pass."));
        mockMvc.perform(auth(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":2}}")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Port " + outputPort + " requires outputSnapshot.attrs.grade."));
        assertEquals("running", data(get("/flow-runs/" + runId + "/steps")).get(0).path("status").asText());
        data(put("/process-ios/" + outputPort).contentType(MediaType.APPLICATION_JSON)
            .content("{\"validationRule\":\"quantity >= 99\"}"));
        data(post("/flow-runs/" + runId + "/steps/" + rootId + "/complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":2,\"attrs\":{\"grade\":\"A\"}}}"));
        assertEquals(2, data(get("/flow-runs/" + runId + "/steps")).size());
    }

    private GraphFixture graph(String policy, String condition, Integer capacity) throws Exception {
        String workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("projectId", DEMO_PROJECT, "workflowName", "Graph " + UUID.randomUUID()))))
            .path("workflowId").asText();
        String source = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Source")))).path("processId").asText();
        String target = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "processName", "Target")))).path("processId").asText();
        var body = mapper.createObjectNode().put("workflowId", workflow).put("fromProcessId", source)
            .put("toProcessId", target).put("failurePolicy", policy);
        if (condition != null) body.put("conditionExpr", condition);
        if (capacity != null) body.put("capacity", capacity);
        String connection = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(json(body))).path("connectionId").asText();
        String revision = data(post("/workflows/" + workflow + "/revisions"))
            .path("workflowRevisionId").asText();
        return new GraphFixture(workflow, source, target, connection, revision);
    }

    private String connect(String workflow, String source, String target) throws Exception {
        return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "fromProcessId", source, "toProcessId", target))))
            .path("connectionId").asText();
    }

    private String startGraph(GraphFixture fixture) throws Exception {
        String runId = data(post("/flow-runs/graph").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", fixture.workflow(),
                "workflowRevisionId", fixture.revision(), "runType", "test"))))
            .path("flowRunId").asText();
        assertNotEquals("", runId);
        return runId;
    }

    private String json(Object body) throws Exception { return mapper.writeValueAsString(body); }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }

    private record GraphFixture(String workflow, String source, String target, String connection, String revision) {}
}
