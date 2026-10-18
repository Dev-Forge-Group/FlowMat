package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Connection loss rates in graph runs (docs/architecture/adr/ADR-009-connection-loss-rate.md decisions 1-11). */
@AutoConfigureMockMvc
class ConnectionLossIntegrationTest extends IntegrationTestSupport {
    private static final String RANGE = "lossRate is a fraction of at least 0 and below 1 (0.05 is 5%).";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aConnectionLosesItsRateOnTheWayAndSaysHowMuch() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        String ab = g.link("A", "B", Map.of("lossRate", 0.02));
        JsonNode revision = g.publish();
        // Loss rates apply from schemaVersion 2 (ADR-009 decision 8).
        assertTrue(revision.path("schemaVersion").asInt() >= 2);
        String run = g.start(revision.path("workflowRevisionId").asText());
        complete(run, g, "A", output(100));
        JsonNode input = one(run, g, "B").path("inputSnapshot");
        assertQuantity("98", input);
        assertEquals("kg", input.path("unit").asText());
        JsonNode loss = event(run, one(run, g, "A").path("stepId").asText(), "transfer_loss").path("payload");
        assertEquals(ab, loss.path("connectionId").asText());
        assertEquals(g.id("B"), loss.path("targetNodeId").asText());
        assertNumber("100", loss.path("sent"));
        assertNumber("0.02", loss.path("lossRate"));
        assertNumber("2", loss.path("lost"));
        assertNumber("98", loss.path("arrived"));
        assertEquals("kg", loss.path("unit").asText());
    }

    @Test
    void sharesLoseOnTheirOwnConnectionsAndAJoinAddsWhatArrived() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "End")) g.node(name, "process");
        g.node("S", "split");
        g.node("J", "join");
        g.link("A", "S");
        String toB = g.link("S", "B", share(70, 0.01));
        g.link("S", "C", share(10, null));
        g.link("S", "J", share(20, 0.1));
        g.link("B", "J", Map.of("lossRate", 0.5));
        g.link("C", "J");
        g.link("J", "End");
        String run = g.start(g.publish().path("workflowRevisionId").asText());
        complete(run, g, "A", output(100));
        assertQuantity("69.3", one(run, g, "B").path("inputSnapshot"));
        assertQuantity("10", one(run, g, "C").path("inputSnapshot"));
        JsonNode routed = events(run, "gateway_routed").stream()
            .filter(one -> toB.equals(one.path("payload").path("connectionId").asText())).findFirst().orElseThrow();
        assertNumber("70", routed.path("payload").path("quantity"));
        JsonNode waiting = one(run, g, "J");
        assertQuantity("18", waiting.path("inputSnapshot").path("arrivals").get(0));

        complete(run, g, "B", Map.of("quantity", new BigDecimal("69.3"), "unit", "kg"));
        complete(run, g, "C", output(10));
        // 18 straight from the Split, half of B's 69.3 and C's 10.
        assertQuantity("62.65", one(run, g, "J").path("outputSnapshot"));
        assertQuantity("62.65", one(run, g, "End").path("inputSnapshot"));
    }

    @Test
    void capacityAndTheOutputPortSeeWhatIsSentTheInputPortWhatArrives() throws Exception {
        Graph full = new Graph();
        full.node("A", "process");
        full.node("B", "process");
        full.link("A", "B", Map.of("lossRate", 0.02, "capacity", 99));
        String run = full.start(full.publish().path("workflowRevisionId").asText());
        String a = one(run, full, "A").path("stepId").asText();
        data(post(step(run, a, "start")));
        report(run, a, output(100)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity exceeded")));

        Graph ports = new Graph();
        ports.node("A", "process");
        ports.node("B", "process");
        String out = ports.port("A", "output", "quantity >= 100");
        String in = ports.port("B", "input", "quantity < 99");
        ports.link("A", "B", Map.of("lossRate", 0.02, "fromIoId", out, "toIoId", in));
        String second = ports.start(ports.publish().path("workflowRevisionId").asText());
        complete(second, ports, "A", output(100));
        assertQuantity("98", one(second, ports, "B").path("inputSnapshot"));
    }

    @Test
    void whatIsAllLostDoesNotGoOnEvenFromAnExclusiveGateway() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C")) g.node(name, "process");
        g.node("X", "exclusive");
        String toB = g.link("A", "B", Map.of("lossRate", 0.6));
        g.link("A", "X");
        g.link("X", "C", Map.of("lossRate", 0.6));
        String run = g.start(g.publish().path("workflowRevisionId").asText());
        complete(run, g, "A", Map.of("quantity", new BigDecimal("0.0001"), "unit", "kg"));
        assertTrue(forNode(steps(run), g.id("B")).isEmpty());
        assertTrue(forNode(steps(run), g.id("C")).isEmpty());
        assertEquals("completed", one(run, g, "X").path("status").asText());
        assertEquals("loss_empty", events(run, "connection_filtered").stream()
            .filter(one -> toB.equals(one.path("payload").path("connectionId").asText())).findFirst().orElseThrow()
            .path("payload").path("reason").asText());
        JsonNode loss = events(run, "transfer_loss").stream()
            .filter(one -> toB.equals(one.path("payload").path("connectionId").asText())).findFirst().orElseThrow()
            .path("payload");
        assertNumber("0.0001", loss.path("lost"));
        assertNumber("0", loss.path("arrived"));
        // Nothing is left to wait for, so the run can be finished.
        assertEquals("finished", data(post("/flow-runs/" + run + "/finish").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).path("status").asText());
    }

    @Test
    void aRevisionPublishedBeforeTheLossCountedRunsAsBefore() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        g.link("A", "B", Map.of("lossRate", 0.02));
        String revision = g.publish().path("workflowRevisionId").asText();
        jdbc.update("UPDATE workflow_revision SET schema_version = 1, snapshot_json = jsonb_set(snapshot_json, '{schemaVersion}', '1')"
            + " WHERE workflow_revision_id = ?", revision);
        String run = g.start(revision);
        complete(run, g, "A", output(100));
        assertQuantity("100", one(run, g, "B").path("inputSnapshot"));
        assertTrue(events(run, "transfer_loss").isEmpty());
        assertEquals(1, revision(g, revision).path("schemaVersion").asInt());
    }

    @Test
    void lossRatesAreFractionsBelowOneWhenSavedAndWhenPublished() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        refused(g, Map.of("lossRate", 1));
        refused(g, Map.of("lossRate", -0.1));
        String ab = g.link("A", "B", Map.of("lossRate", 0.9999));
        call(put("/process-connections/" + ab).contentType(MediaType.APPLICATION_JSON).content("{\"lossRate\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RANGE));
        assertNumber("0.9999", data(get("/process-connections/" + ab)).path("lossRate"));

        // A value saved before the rule: publishing refuses it.
        jdbc.update("UPDATE process_connection SET loss_rate = 1.5 WHERE connection_id = ?", ab);
        JsonNode issue = null;
        for (JsonNode one : data(get("/workflows/" + g.workflow + "/validation")).path("issues")) {
            if ("LOSS_RATE_RANGE".equals(one.path("code").asText())) issue = one;
        }
        assertTrue(issue != null, "LOSS_RATE_RANGE");
        assertEquals("error", issue.path("severity").asText());
        assertEquals(ab, issue.path("connectionId").asText());
        call(post("/workflows/" + g.workflow + "/revisions")).andExpect(status().isConflict());
    }

    private void refused(Graph g, Map<String, ?> options) throws Exception {
        Map<String, Object> body = new HashMap<>(options);
        body.put("workflowId", g.workflow);
        body.put("fromProcessId", g.id("A"));
        body.put("toProcessId", g.id("B"));
        call(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RANGE));
    }

    private static Map<String, Object> share(int percent, Double lossRate) {
        Map<String, Object> share = new HashMap<>();
        share.put("splitKind", "share");
        share.put("splitValue", percent);
        if (lossRate != null) share.put("lossRate", lossRate);
        return share;
    }

    private static void assertQuantity(String expected, JsonNode snapshot) {
        assertNumber(expected, snapshot.path("quantity"));
    }

    private static void assertNumber(String expected, JsonNode value) {
        assertTrue(value.isNumber(), "number " + value);
        assertEquals(0, new BigDecimal(expected).compareTo(value.decimalValue()), "value " + value);
    }

    private JsonNode revision(Graph g, String revision) throws Exception {
        return data(get("/workflows/" + g.workflow + "/revisions/" + revision));
    }

    /** A workflow in a project of its own, so nothing else's runs or names get in the way. */
    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();

        Graph() throws Exception {
            String project = data(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectName", "Loss " + UUID.randomUUID(), "ownerId", DEMO_OWNER)))).path("projectId").asText();
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectId", project, "workflowName", "Loss")))).path("workflowId").asText();
        }

        String id(String name) {
            return nodes.get(name);
        }

        void node(String name, String type) throws Exception {
            nodes.put(name, data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "processName", name, "nodeType", type)))).path("processId").asText());
        }

        String port(String name, String direction, String rule) throws Exception {
            return data(post("/process-ios").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("processId", id(name), "direction", direction, "quantity", 1, "unit", "kg", "validationRule", rule))))
                .path("processIoId").asText();
        }

        String link(String from, String to) throws Exception {
            return link(from, to, Map.of());
        }

        String link(String from, String to, Map<String, ?> options) throws Exception {
            Map<String, Object> body = new HashMap<>(options);
            body.put("workflowId", workflow);
            body.put("fromProcessId", id(from));
            body.put("toProcessId", id(to));
            return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .path("connectionId").asText();
        }

        JsonNode publish() throws Exception {
            return data(post("/workflows/" + workflow + "/revisions"));
        }

        String start(String revision) throws Exception {
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

    private ResultActions report(String run, String stepId, Map<String, ?> output) throws Exception {
        return call(post(step(run, stepId, "complete")).contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("outputSnapshot", output))));
    }

    private static Map<String, ?> output(int quantity) {
        return Map.of("quantity", quantity, "unit", "kg");
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
