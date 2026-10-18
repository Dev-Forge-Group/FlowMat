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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Split gateways in graph runs (docs/architecture/adr/ADR-008-quantity-split.md decisions 1-12). */
@AutoConfigureMockMvc
class SplitGatewayIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void percentsDivideTheWholeAndAJoinAddsItBack() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "End")) g.node(name, "process");
        g.node("S", "split");
        g.node("J", "join");
        g.link("A", "S");
        String toB = g.link("S", "B", share("share", 70));
        g.link("S", "C", share("share", 30));
        g.link("B", "J");
        g.link("C", "J");
        g.link("J", "End");
        String run = g.start();
        complete(run, g, "A", output(100));
        JsonNode split = one(run, g, "S");
        assertEquals("completed", split.path("status").asText());
        assertEquals("system", event(run, split.path("stepId").asText(), "step_completed").path("actorType").asText());
        assertQuantity("70", one(run, g, "B").path("inputSnapshot"));
        assertQuantity("30", one(run, g, "C").path("inputSnapshot"));
        assertEquals("kg", one(run, g, "C").path("inputSnapshot").path("unit").asText());
        JsonNode routed = events(run, "gateway_routed").stream()
            .filter(one -> toB.equals(one.path("payload").path("connectionId").asText())).findFirst().orElseThrow();
        assertEquals("split", routed.path("payload").path("reason").asText());
        assertQuantity("70", routed.path("payload"));

        complete(run, g, "B", output(70));
        complete(run, g, "C", output(30));
        assertQuantity("100", one(run, g, "J").path("outputSnapshot"));
    }

    @Test
    void fixedQuantitiesTheRestAndRoundingKeepTheWhole() throws Exception {
        // A sample of 0.5 and the rest.
        Graph sample = new Graph();
        for (String name : List.of("A", "B", "C")) sample.node(name, "process");
        sample.node("S", "split");
        sample.link("A", "S");
        sample.link("S", "B", share("fixed", 0.5));
        sample.link("S", "C", share("rest", null));
        String run = sample.start();
        complete(run, sample, "A", output(100));
        assertQuantity("0.5", one(run, sample, "B").path("inputSnapshot"));
        assertQuantity("99.5", one(run, sample, "C").path("inputSnapshot"));

        // Percents of exactly 100 without a rest: the last one drawn takes the rounding difference.
        Graph thirds = new Graph();
        for (String name : List.of("A", "B", "C", "D")) thirds.node(name, "process");
        thirds.node("S", "split");
        thirds.link("A", "S");
        thirds.link("S", "B", share("share", 33.33));
        thirds.link("S", "C", share("share", 33.33));
        thirds.link("S", "D", share("share", 33.34));
        String second = thirds.start();
        complete(second, thirds, "A", Map.of("quantity", new BigDecimal("0.001"), "unit", "kg"));
        assertQuantity("0.0003", one(second, thirds, "B").path("inputSnapshot"));
        assertQuantity("0.0003", one(second, thirds, "C").path("inputSnapshot"));
        assertQuantity("0.0004", one(second, thirds, "D").path("inputSnapshot"));

        // A share of 0 does not go on.
        Graph exact = new Graph();
        for (String name : List.of("A", "B", "C")) exact.node(name, "process");
        exact.node("S", "split");
        exact.link("A", "S");
        exact.link("S", "B", share("fixed", 5));
        String toRest = exact.link("S", "C", share("rest", null));
        String third = exact.start();
        complete(third, exact, "A", output(5));
        assertQuantity("5", one(third, exact, "B").path("inputSnapshot"));
        assertTrue(forNode(steps(third), exact.id("C")).isEmpty());
        assertEquals("split_empty", events(third, "connection_filtered").stream()
            .filter(one -> toRest.equals(one.path("payload").path("connectionId").asText())).findFirst().orElseThrow()
            .path("payload").path("reason").asText());
    }

    @Test
    void tooLittleOrNoQuantityRejectsTheReportOrFailsTheRun() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C")) g.node(name, "process");
        g.node("S", "split");
        g.link("A", "S");
        g.link("S", "B", share("fixed", 3));
        g.link("S", "C", share("rest", null));
        String run = g.start();
        String a = one(run, g, "A").path("stepId").asText();
        data(post(step(run, a, "start")));
        report(run, a, output(2)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Split " + g.id("S") + " gives out 3 but has 2."));
        report(run, a, Map.of("unit", "kg")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Split " + g.id("S") + " needs outputSnapshot.quantity to divide."));
        assertEquals("running", one(run, g, "A").path("status").asText());
        accepted(report(run, a, output(3)));
        assertQuantity("3", one(run, g, "B").path("inputSnapshot"));

        // After a Join that a skip let through, nobody can send the report again: the Split fails and so does the run.
        Graph joined = new Graph();
        for (String name : List.of("A", "B", "C", "D", "E")) joined.node(name, "process");
        joined.node("J", "join");
        joined.node("S", "split");
        joined.link("A", "B");
        joined.link("A", "C", Map.of("failurePolicy", "skip"));
        joined.link("B", "J");
        joined.link("C", "J");
        joined.link("J", "S");
        joined.link("S", "D", share("fixed", 3));
        joined.link("S", "E", share("rest", null));
        String second = joined.start();
        complete(second, joined, "A", Map.of());
        complete(second, joined, "B", output(2));
        String c = one(second, joined, "C").path("stepId").asText();
        data(post(step(second, c, "start")));
        data(post(step(second, c, "fail")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"errorCode\":\"CHECK\",\"errorMessage\":\"Line check\"}"));
        assertEquals("SPLIT_REJECTED", one(second, joined, "S").path("errorCode").asText());
        assertEquals("failed", data(get("/flow-runs/" + second)).path("status").asText());
        assertTrue(forNode(steps(second), joined.id("D")).isEmpty());
    }

    @Test
    void aShareThatGoesStraightIntoAJoinArrivesAsTheShare() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "End")) g.node(name, "process");
        g.node("S", "split");
        g.node("J", "join");
        g.link("A", "S");
        g.link("S", "J", share("share", 30));
        g.link("S", "B", share("share", 70));
        g.link("B", "J");
        g.link("J", "End");
        String run = g.start();
        complete(run, g, "A", output(100));
        JsonNode waiting = one(run, g, "J");
        assertEquals("planned", waiting.path("status").asText());
        assertQuantity("30", waiting.path("inputSnapshot").path("arrivals").get(0));
        complete(run, g, "B", output(70));
        assertQuantity("100", one(run, g, "J").path("outputSnapshot"));
    }

    @Test
    void sharesAreCheckedWhenSavedAndWhenPublished() throws Exception {
        Graph bad = new Graph();
        for (String name : List.of("B", "C", "D", "E", "X", "Y")) bad.node(name, "process");
        bad.node("S", "split");
        String plain = bad.link("X", "Y", share("share", 50));
        bad.link("S", "B", Map.of("conditionExpr", "quantity > 1"));
        bad.link("S", "C", share("rest", null));
        bad.link("S", "D", share("rest", null));
        List<String> codes = texts(data(get("/workflows/" + bad.workflow + "/validation")).path("issues"), "code");
        assertTrue(codes.containsAll(List.of("SPLIT_WITHOUT_INPUT", "SPLIT_SHARE_MISSING", "SPLIT_CONDITION",
            "SPLIT_REST_DUPLICATE", "SPLIT_OUTSIDE_GATEWAY")), codes.toString());
        call(post("/workflows/" + bad.workflow + "/revisions")).andExpect(status().isConflict());

        Graph over = new Graph();
        for (String name : List.of("A", "B", "C")) over.node(name, "process");
        over.node("S", "split");
        over.link("A", "S");
        over.link("S", "B", share("share", 70));
        over.link("S", "C", share("share", 40));
        assertTrue(texts(data(get("/workflows/" + over.workflow + "/validation")).path("issues"), "code").contains("SPLIT_SHARES_OVER"));
        Graph noRest = new Graph();
        for (String name : List.of("A", "B", "C")) noRest.node(name, "process");
        noRest.node("S", "split");
        noRest.link("A", "S");
        noRest.link("S", "B", share("share", 50));
        noRest.link("S", "C", share("fixed", 1));
        assertTrue(texts(data(get("/workflows/" + noRest.workflow + "/validation")).path("issues"), "code").contains("SPLIT_NO_REST"));
        Graph single = new Graph();
        for (String name : List.of("A", "B")) single.node(name, "process");
        single.node("S", "split");
        single.link("A", "S");
        single.link("S", "B", share("rest", null));
        assertTrue(texts(data(get("/workflows/" + single.workflow + "/validation")).path("issues"), "code").contains("SPLIT_SINGLE_ROUTE"));

        refused(bad, "S", "E", share("half", 50), "splitKind is share, fixed or rest.");
        refused(bad, "S", "E", share("share", 0), "A share split takes a percent above 0 and at most 100, with up to 2 decimals.");
        refused(bad, "S", "E", share("share", 12.345), "A share split takes a percent above 0 and at most 100, with up to 2 decimals.");
        refused(bad, "S", "E", share("fixed", -1), "A fixed split takes a quantity above 0 with up to 4 decimals.");
        refused(bad, "S", "E", Map.of("splitKind", "rest", "splitValue", 1), "A rest split takes no splitValue.");
        refused(bad, "S", "E", Map.of("splitValue", 1), "splitValue needs splitKind.");
        call(put("/process-connections/" + plain).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clearSplit\":true,\"splitKind\":\"rest\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("splitKind and splitValue cannot be supplied with clearSplit."));
        JsonNode cleared = data(put("/process-connections/" + plain).contentType(MediaType.APPLICATION_JSON).content("{\"clearSplit\":true}"));
        assertTrue(cleared.path("splitKind").isNull());
        assertTrue(cleared.path("splitValue").isNull());
    }

    private void refused(Graph g, String from, String to, Map<String, ?> options, String message) throws Exception {
        Map<String, Object> body = new HashMap<>(options);
        body.put("workflowId", g.workflow);
        body.put("fromProcessId", g.id(from));
        body.put("toProcessId", g.id(to));
        call(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private static Map<String, Object> share(String kind, Object value) {
        Map<String, Object> share = new HashMap<>();
        share.put("splitKind", kind);
        if (value != null) share.put("splitValue", value);
        return share;
    }

    private static void assertQuantity(String expected, JsonNode snapshot) {
        assertEquals(0, new BigDecimal(expected).compareTo(snapshot.path("quantity").decimalValue()),
            "quantity " + snapshot.path("quantity"));
    }

    /** A workflow in a project of its own, so nothing else's runs or names get in the way. */
    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();

        Graph() throws Exception {
            String project = data(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectName", "Split " + UUID.randomUUID(), "ownerId", DEMO_OWNER)))).path("projectId").asText();
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectId", project, "workflowName", "Split")))).path("workflowId").asText();
        }

        String id(String name) {
            return nodes.get(name);
        }

        void node(String name, String type) throws Exception {
            nodes.put(name, data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "processName", name, "nodeType", type)))).path("processId").asText());
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

    private static List<String> texts(JsonNode array, String field) {
        List<String> found = new ArrayList<>();
        array.forEach(row -> found.add(row.path(field).asText()));
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
