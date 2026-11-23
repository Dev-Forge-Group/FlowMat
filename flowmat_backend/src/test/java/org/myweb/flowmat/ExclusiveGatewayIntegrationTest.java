package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Exclusive gateways in graph runs (docs/architecture/adr/ADR-006-operation-types.md decisions 1-9). */
@AutoConfigureMockMvc
class ExclusiveGatewayIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void theFirstMatchingConnectionByPriorityGoesOnAndTheDefaultCatchesTheRest() throws Exception {
        String workflow = workflow();
        String measure = process(workflow, "Measure", null);
        String choose = process(workflow, "Choose", "exclusive");
        String big = process(workflow, "Big", null);
        String medium = process(workflow, "Medium", null);
        String rest = process(workflow, "Rest", null);
        connect(workflow, measure, choose, null, null, null);
        String toBig = connect(workflow, choose, big, 1, "quantity > 10", null);
        String toMedium = connect(workflow, choose, medium, 2, "quantity > 5", null);
        String toRest = connect(workflow, choose, rest, 9, null, true);
        assertEquals(0, data(get("/workflows/" + workflow + "/validation")).path("errors").asInt());
        String revision = publish(workflow);

        // 12 holds for both Big and Medium; Big has the lower priority and is the only one that goes on.
        String first = run(workflow, revision, 12);
        JsonNode steps = data(get("/flow-runs/" + first + "/steps"));
        assertEquals(List.of(measure, choose, big), nodes(steps));
        JsonNode gateway = steps.get(1);
        assertEquals("completed", gateway.path("status").asText());
        assertEquals(12, gateway.path("outputSnapshot").path("quantity").asInt());
        assertEquals("planned", steps.get(2).path("status").asText());
        assertEquals(toBig, steps.get(2).path("sourceConnectionId").asText());
        JsonNode events = data(get("/flow-runs/" + first + "/events"));
        JsonNode completed = event(events, gateway.path("stepId").asText(), "step_completed", null);
        assertEquals("system", completed.path("actorType").asText());
        assertEquals("first_match", event(events, gateway.path("stepId").asText(), "gateway_routed", toBig)
            .path("payload").path("reason").asText());
        assertEquals("exclusive_not_chosen", event(events, gateway.path("stepId").asText(), "connection_filtered", toMedium)
            .path("payload").path("reason").asText());
        assertEquals("default_not_needed", event(events, gateway.path("stepId").asText(), "connection_filtered", toRest)
            .path("payload").path("reason").asText());
        // The gateway step cannot be started or reported by hand.
        mockMvc.perform(auth(post("/flow-runs/" + first + "/steps/" + gateway.path("stepId").asText() + "/start")))
            .andExpect(status().isConflict());

        // 7: only Medium's condition holds. 3: none holds, so the default goes on.
        assertEquals(medium, nodes(data(get("/flow-runs/" + run(workflow, revision, 7) + "/steps"))).get(2));
        String third = run(workflow, revision, 3);
        JsonNode thirdSteps = data(get("/flow-runs/" + third + "/steps"));
        assertEquals(rest, nodes(thirdSteps).get(2));
        assertEquals("default", event(data(get("/flow-runs/" + third + "/events")), thirdSteps.get(1).path("stepId").asText(),
            "gateway_routed", toRest).path("payload").path("reason").asText());

        // Without a default and with no condition holding, the gateway step fails with NO_ROUTE and so does the run.
        data(put("/process-connections/" + toRest).contentType(MediaType.APPLICATION_JSON)
            .content("{\"defaultRoute\":false,\"conditionExpr\":\"quantity > 100\"}"));
        String strict = publish(workflow);
        String failed = run(workflow, strict, 3);
        assertEquals("failed", data(get("/flow-runs/" + failed)).path("status").asText());
        JsonNode failedSteps = data(get("/flow-runs/" + failed + "/steps"));
        assertEquals(2, failedSteps.size());
        assertEquals("failed", failedSteps.get(1).path("status").asText());
        assertEquals("NO_ROUTE", failedSteps.get(1).path("errorCode").asText());
        assertEquals("NO_ROUTE", event(data(get("/flow-runs/" + failed + "/events")), null, "run_failed", null)
            .path("payload").path("errorCode").asText());
        // The first revision keeps its default: 3 still goes to Rest there.
        assertEquals(rest, nodes(data(get("/flow-runs/" + run(workflow, revision, 3) + "/steps"))).get(2));
    }

    @Test
    void publishingRefusesTwoDefaultsDefaultsOutsideAGatewayAndAGatewayWithoutInput() throws Exception {
        String workflow = workflow();
        String choose = process(workflow, "Choose", "exclusive");
        String left = process(workflow, "Left", null);
        String right = process(workflow, "Right", null);
        connect(workflow, choose, left, 1, null, true);
        connect(workflow, choose, right, 2, null, true);
        JsonNode validation = data(get("/workflows/" + workflow + "/validation"));
        assertEquals(2, validation.path("errors").asInt());
        assertTrue(codes(validation).containsAll(List.of("GATEWAY_DEFAULTS", "GATEWAY_WITHOUT_INPUT")));
        mockMvc.perform(auth(post("/workflows/" + workflow + "/revisions"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("GATEWAY_DEFAULTS")));

        String plain = workflow();
        String from = process(plain, "From", null);
        String to = process(plain, "To", null);
        connect(plain, from, to, null, null, true);
        mockMvc.perform(auth(post("/workflows/" + plain + "/revisions"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("DEFAULT_ROUTE_OUTSIDE_GATEWAY")));

        String lonely = workflow();
        String start = process(lonely, "Start", null);
        String gate = process(lonely, "Gate", "exclusive");
        String end = process(lonely, "End", null);
        connect(lonely, start, gate, null, null, null);
        connect(lonely, gate, end, 3, "quantity > 1", null);
        assertTrue(codes(data(get("/workflows/" + lonely + "/validation"))).contains("GATEWAY_SINGLE_ROUTE"));

        mockMvc.perform(auth(post("/process-connections")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + plain + "\",\"fromProcessId\":\"" + from + "\",\"toProcessId\":\"" + to
                    + "\",\"defaultRoute\":\"yes\"}"))
            .andExpect(status().isBadRequest());
    }

    private String workflow() throws Exception {
        return data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("projectId", DEMO_PROJECT, "workflowName", "Gateway " + UUID.randomUUID()))))
            .path("workflowId").asText();
    }

    private String process(String workflow, String name, String nodeType) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("workflowId", workflow).put("processName", name);
        if (nodeType != null) body.put("nodeType", nodeType);
        return data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(json(body))).path("processId").asText();
    }

    private String connect(String workflow, String source, String target, Integer priority, String condition, Boolean defaultRoute)
        throws Exception {
        ObjectNode body = mapper.createObjectNode().put("workflowId", workflow).put("fromProcessId", source)
            .put("toProcessId", target);
        if (priority != null) body.put("priority", priority);
        if (condition != null) body.put("conditionExpr", condition);
        if (defaultRoute != null) body.put("defaultRoute", defaultRoute);
        return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)))
            .path("connectionId").asText();
    }

    private String publish(String workflow) throws Exception {
        return data(post("/workflows/" + workflow + "/revisions")).path("workflowRevisionId").asText();
    }

    /** Starts a graph run and reports its first step done with this quantity. */
    private String run(String workflow, String revision, int quantity) throws Exception {
        String runId = data(post("/flow-runs/graph").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("workflowId", workflow, "workflowRevisionId", revision, "runType", "test"))))
            .path("flowRunId").asText();
        String root = data(get("/flow-runs/" + runId + "/steps")).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + root + "/start"));
        data(post("/flow-runs/" + runId + "/steps/" + root + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":" + quantity + "}}"));
        return runId;
    }

    private static List<String> nodes(JsonNode steps) {
        List<String> nodes = new ArrayList<>();
        steps.forEach(step -> nodes.add(step.path("nodeId").asText()));
        return nodes;
    }

    private static List<String> codes(JsonNode validation) {
        List<String> codes = new ArrayList<>();
        validation.path("issues").forEach(issue -> codes.add(issue.path("code").asText()));
        return codes;
    }

    private static JsonNode event(JsonNode events, String stepId, String type, String connectionId) {
        for (JsonNode event : events) {
            if (type.equals(event.path("eventType").asText())
                && (stepId == null || stepId.equals(event.path("stepId").asText()))
                && (connectionId == null || connectionId.equals(event.path("payload").path("connectionId").asText()))) {
                return event;
            }
        }
        throw new AssertionError("No " + type + " event for " + connectionId);
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
