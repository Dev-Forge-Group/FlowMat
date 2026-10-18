package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Join gateways in graph runs (docs/architecture/adr/ADR-007-join-gateway.md decisions 1-11). */
@AutoConfigureMockMvc
class JoinGatewayIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void twoBranchesAreJoinedOnceWhenTheLastOneArrives() throws Exception {
        Graph g = diamond(Map.of(), Map.of());
        String run = g.start();
        complete(run, g, "A", Map.of());
        complete(run, g, "B", output(2, "kg", "B"));
        // C can still bring something: the Join waits, and neither people nor schedules move it.
        JsonNode waiting = one(run, g, "J");
        assertEquals("planned", waiting.path("status").asText());
        assertEquals(1, waiting.path("joinWaiting").asInt());
        assertEquals(1, waiting.path("inputSnapshot").path("arrivals").size());
        String join = waiting.path("stepId").asText();
        call(post(step(run, join, "start"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("A Join step waits for its branches; the server passes it on (ADR-007)."));
        call(put(step(run, join, "schedule")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduledAt\":\"2031-01-01T00:00:00Z\"}")).andExpect(status().isConflict());
        assertTrue(forNode(steps(run), g.id("End")).isEmpty());

        complete(run, g, "C", output(3, "kg", "C"));
        JsonNode joined = one(run, g, "J");
        assertEquals(join, joined.path("stepId").asText());
        assertEquals("completed", joined.path("status").asText());
        assertEquals(0, joined.path("joinWaiting").asInt());
        JsonNode merged = joined.path("outputSnapshot");
        assertEquals(5, merged.path("quantity").asInt());
        assertEquals("kg", merged.path("unit").asText());
        assertFalse(merged.has("attrs"));
        assertEquals(List.of("B", "C"), texts(merged.path("arrivals"), "attrs", "tag"));
        assertEquals(merged, joined.path("inputSnapshot"));
        assertEquals("system", event(run, join, "step_completed").path("actorType").asText());
        assertEquals(2, events(run, "join_arrived").size());
        assertEquals(merged, one(run, g, "End").path("inputSnapshot"));

        // The step after the Join has both branches among its ancestors, earliest first.
        JsonNode lineage = data(get(step(run, one(run, g, "End").path("stepId").asText(), "lineage")));
        assertEquals(List.of(g.id("A"), g.id("B"), g.id("C"), g.id("J")), texts(lineage.path("ancestors"), "nodeId"));
        JsonNode fromStart = data(get(step(run, one(run, g, "A").path("stepId").asText(), "lineage")));
        assertEquals(List.of(g.id("B"), g.id("C"), g.id("J"), g.id("End")), texts(fromStart.path("descendants"), "nodeId"));
        complete(run, g, "End", Map.of());
        assertEquals("finished", data(post("/flow-runs/" + run + "/finish").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).path("status").asText());
    }

    @Test
    void branchesThatEndElsewhereDoNotKeepTheJoinWaiting() throws Exception {
        // A skipped branch: the Join goes on with what arrived.
        Graph skipped = diamond(Map.of("failurePolicy", "skip"), Map.of());
        String run = skipped.start();
        complete(run, skipped, "A", Map.of());
        complete(run, skipped, "B", output(2, "kg", "B"));
        fail(run, skipped, "C");
        assertEquals("skipped", one(run, skipped, "C").path("status").asText());
        assertEquals("completed", one(run, skipped, "J").path("status").asText());
        assertEquals(1, one(run, skipped, "J").path("outputSnapshot").path("arrivals").size());
        assertEquals(1, forNode(steps(run), skipped.id("End")).size());

        // An Exclusive that does not choose a branch: nothing waits for it.
        Graph chosen = new Graph();
        for (String name : List.of("A", "B", "C", "End")) chosen.node(name, "process");
        chosen.node("X", "exclusive");
        chosen.node("J", "join");
        chosen.link("A", "X");
        chosen.link("X", "B", Map.of("priority", 1, "conditionExpr", "quantity > 10"));
        chosen.link("X", "C", Map.of("defaultRoute", true));
        chosen.link("B", "J");
        chosen.link("C", "J");
        chosen.link("J", "End");
        String second = chosen.start();
        complete(second, chosen, "A", output(12, "kg", "A"));
        assertTrue(forNode(steps(second), chosen.id("C")).isEmpty());
        complete(second, chosen, "B", output(2, "kg", "B"));
        assertEquals("completed", one(second, chosen, "J").path("status").asText());

        // No branch arrives: no Join and nothing after it, and the run can finish.
        Graph none = new Graph();
        for (String name : List.of("A", "B", "C", "End")) none.node(name, "process");
        none.node("J", "join");
        none.link("A", "B", Map.of("conditionExpr", "quantity > 100"));
        none.link("A", "C", Map.of("conditionExpr", "quantity > 100"));
        none.link("B", "J");
        none.link("C", "J");
        none.link("J", "End");
        String third = none.start();
        complete(third, none, "A", output(1, "kg", "A"));
        assertEquals(1, steps(third).size());
        assertEquals("finished", data(post("/flow-runs/" + third + "/finish").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).path("status").asText());
    }

    @Test
    void aJoinedOutputThatAConnectionRefusesRejectsTheReportOrFailsTheRun() throws Exception {
        // The last branch's report would make 5 for a connection that holds 4: refused, nothing kept, sent again with 1.
        Graph capped = diamond(Map.of(), Map.of("capacity", 4));
        String run = capped.start();
        complete(run, capped, "A", Map.of());
        complete(run, capped, "B", output(2, "kg", "B"));
        String c = one(run, capped, "C").path("stepId").asText();
        data(post(step(run, c, "start")));
        report(run, c, output(3, "kg", "C")).andExpect(status().isConflict());
        assertEquals("running", one(run, capped, "C").path("status").asText());
        assertEquals(1, one(run, capped, "J").path("inputSnapshot").path("arrivals").size());
        accepted(report(run, c, output(1, "kg", "C")));
        assertEquals(3, one(run, capped, "J").path("outputSnapshot").path("quantity").asInt());

        // A skip leaves no report to refuse: the Join fails and so does the run; the skip stays.
        Graph skipped = diamond(Map.of("failurePolicy", "skip"), Map.of("capacity", 1));
        String second = skipped.start();
        complete(second, skipped, "A", Map.of());
        complete(second, skipped, "B", output(2, "kg", "B"));
        fail(second, skipped, "C");
        assertEquals("skipped", one(second, skipped, "C").path("status").asText());
        assertEquals("JOIN_TRANSFER_REJECTED", one(second, skipped, "J").path("errorCode").asText());
        assertEquals("failed", data(get("/flow-runs/" + second)).path("status").asText());
        assertTrue(forNode(steps(second), skipped.id("End")).isEmpty());
    }

    @Test
    void publishingChecksAJoinsIncomingConnections() throws Exception {
        Graph missing = new Graph();
        missing.node("J", "join");
        missing.node("End", "process");
        missing.link("J", "End");
        assertTrue(texts(data(get("/workflows/" + missing.workflow + "/validation")).path("issues"), "code")
            .contains("JOIN_WITHOUT_INPUT"));
        call(post("/workflows/" + missing.workflow + "/revisions")).andExpect(status().isConflict());
        Graph single = new Graph();
        single.node("A", "process");
        single.node("J", "join");
        single.node("End", "process");
        single.link("A", "J");
        single.link("J", "End");
        assertTrue(texts(data(get("/workflows/" + single.workflow + "/validation")).path("issues"), "code")
            .contains("JOIN_SINGLE_INPUT"));
    }

    /** A → (B, C) → J → End; {@code toC} and {@code toEnd} add to those connections. */
    private Graph diamond(Map<String, ?> toC, Map<String, ?> toEnd) throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "End")) g.node(name, "process");
        g.node("J", "join");
        g.link("A", "B");
        g.link("A", "C", toC);
        g.link("B", "J");
        g.link("C", "J");
        g.link("J", "End", toEnd);
        return g;
    }

    /** A workflow in a project of its own, so nothing else's runs or names get in the way. */
    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();

        Graph() throws Exception {
            String project = data(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectName", "Join " + UUID.randomUUID(), "ownerId", DEMO_OWNER)))).path("projectId").asText();
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectId", project, "workflowName", "Join")))).path("workflowId").asText();
        }

        String id(String name) {
            return nodes.get(name);
        }

        void node(String name, String type) throws Exception {
            nodes.put(name, data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "processName", name, "nodeType", type)))).path("processId").asText());
        }

        void link(String from, String to) throws Exception {
            link(from, to, Map.of());
        }

        void link(String from, String to, Map<String, ?> options) throws Exception {
            Map<String, Object> body = new HashMap<>(options);
            body.put("workflowId", workflow);
            body.put("fromProcessId", id(from));
            body.put("toProcessId", id(to));
            data(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)));
        }

        String start() throws Exception {
            String revision = data(post("/workflows/" + workflow + "/revisions")).path("workflowRevisionId").asText();
            return data(post("/flow-runs/graph").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "workflowRevisionId", revision, "runType", "test")))).path("flowRunId").asText();
        }
    }

    private void complete(String run, Graph g, String node, Map<String, ?> output) throws Exception {
        JsonNode one = one(run, g, node);
        String id = one.path("stepId").asText();
        if ("planned".equals(one.path("status").asText())) {
            data(post(step(run, id, "start")));
        }
        accepted(report(run, id, output));
    }

    private void fail(String run, Graph g, String node) throws Exception {
        String id = one(run, g, node).path("stepId").asText();
        data(post(step(run, id, "start")));
        data(post(step(run, id, "fail")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"errorCode\":\"CHECK\",\"errorMessage\":\"Line check\"}"));
    }

    private ResultActions report(String run, String stepId, Map<String, ?> output) throws Exception {
        return call(post(step(run, stepId, "complete")).contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("outputSnapshot", output))));
    }

    private static Map<String, ?> output(int quantity, String unit, String tag) {
        return Map.of("quantity", quantity, "unit", unit, "attrs", Map.of("tag", tag));
    }

    private static String step(String run, String stepId, String action) {
        return "/flow-runs/" + run + "/steps/" + stepId + "/" + action;
    }

    private JsonNode steps(String run) throws Exception {
        return data(get("/flow-runs/" + run + "/steps"));
    }

    private JsonNode one(String run, Graph g, String name) throws Exception {
        List<JsonNode> found = forNode(steps(run), g.id(name));
        assertEquals(1, found.size(), "one step for " + name);
        return found.get(0);
    }

    private static List<JsonNode> forNode(JsonNode steps, String nodeId) {
        List<JsonNode> found = new ArrayList<>();
        steps.forEach(one -> {
            if (nodeId.equals(one.path("nodeId").asText())) found.add(one);
        });
        return found;
    }

    private List<JsonNode> events(String run, String type) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        data(get("/flow-runs/" + run + "/events")).forEach(one -> {
            if (type.equals(one.path("eventType").asText())) found.add(one);
        });
        return found;
    }

    private JsonNode event(String run, String stepId, String type) throws Exception {
        return events(run, type).stream().filter(one -> stepId.equals(one.path("stepId").asText())).findFirst()
            .orElseThrow(() -> new AssertionError("No " + type + " for " + stepId));
    }

    private static List<String> texts(JsonNode array, String... path) {
        List<String> found = new ArrayList<>();
        array.forEach(row -> {
            JsonNode value = row;
            for (String field : path) value = value.path(field);
            found.add(value.asText());
        });
        return found;
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return accepted(call(request));
    }

    private JsonNode accepted(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), body.path("message").asText());
        return body.path("data");
    }
}
