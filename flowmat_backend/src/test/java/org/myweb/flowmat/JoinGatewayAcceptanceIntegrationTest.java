package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** ADR-007 acceptance cases. Kept in the isolated review until the lead's Join implementation is ready. */
@AutoConfigureMockMvc
class JoinGatewayAcceptanceIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parallelBranchesWaitThenJoinOnceInEitherCompletionOrder(boolean reverse) throws Exception {
        Graph g = diamond(null, null);
        String run = g.start();
        complete(run, g, "A", Map.of());
        String first = reverse ? "C" : "B", last = reverse ? "B" : "C";
        complete(run, g, first, output(2, "kg", first));
        JsonNode waiting = one(run, g, "J");
        assertEquals("planned", waiting.path("status").asText());
        assertTrue(forNode(steps(run), g.id("End")).isEmpty());
        String joinId = waiting.path("stepId").asText();
        call(post(path(run, joinId, "start"))).andExpect(status().isConflict());
        call(post(path(run, joinId, "complete")).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        complete(run, g, last, output(3, "kg", last));
        JsonNode joined = one(run, g, "J");
        assertEquals(joinId, joined.path("stepId").asText());
        assertEquals("completed", joined.path("status").asText());
        JsonNode snapshot = joined.path("outputSnapshot");
        assertEquals(5, snapshot.path("quantity").asInt());
        assertEquals("kg", snapshot.path("unit").asText());
        assertFalse(snapshot.hasNonNull("attrs"));
        assertEquals(List.of(first, last), texts(snapshot.path("arrivals"), "attrs", "tag"));
        assertEquals(joined.path("inputSnapshot"), snapshot);
        assertEquals(1, data(get(path(run, joinId, "attempts"))).size());
        assertEquals(2, events(run, "join_arrived").size());
        assertEquals(1, events(run, "step_completed").stream()
            .filter(e -> joinId.equals(e.path("stepId").asText()) && "system".equals(e.path("actorType").asText())).count());
        JsonNode end = one(run, g, "End");
        mvc.perform(get(path(run, end.path("stepId").asText(), "lineage"))
            .header("Authorization", "Bearer " + jwt.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
        JsonNode lineage = data(get(path(run, end.path("stepId").asText(), "lineage")));
        assertEquals(4, lineage.path("ancestors").size());
        assertEquals(java.util.Set.of(g.id("A"), g.id("B"), g.id("C"), g.id("J")),
            new java.util.HashSet<>(texts(lineage.path("ancestors"), "nodeId")));
        List<Integer> sequence = new ArrayList<>();
        lineage.path("ancestors").forEach(a -> sequence.add(a.path("sequenceNo").asInt()));
        assertEquals(sequence.stream().sorted().toList(), sequence);
        complete(run, g, "End", Map.of());
        assertEquals("finished", data(post("/flow-runs/" + run + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).path("status").asText());
    }

    @Test
    void nestedJoinsKeepWaitingForReachableUpstreamWork() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "B1", "B2", "End")) g.node(name, "process");
        g.node("J1", "join"); g.node("J2", "join");
        g.link("A", "B"); g.link("A", "C"); g.link("B", "B1"); g.link("B", "B2");
        g.link("B1", "J1"); g.link("B2", "J1"); g.link("J1", "J2"); g.link("C", "J2"); g.link("J2", "End");
        String run = g.start();
        complete(run, g, "A", Map.of()); complete(run, g, "B", Map.of());
        complete(run, g, "B1", output(1, "kg", "B1"));
        complete(run, g, "C", output(4, "kg", "C"));
        assertEquals("planned", one(run, g, "J1").path("status").asText());
        assertEquals("planned", one(run, g, "J2").path("status").asText());
        complete(run, g, "B2", output(2, "kg", "B2"));
        assertEquals("completed", one(run, g, "J1").path("status").asText());
        assertEquals("completed", one(run, g, "J2").path("status").asText());
        assertEquals(7, one(run, g, "J2").path("outputSnapshot").path("quantity").asInt());
        assertEquals(1, forNode(steps(run), g.id("End")).size());
    }

    @Test
    void exclusiveUnselectedBranchDoesNotKeepTheJoinWaiting() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "End")) g.node(name, "process");
        g.node("X", "exclusive"); g.node("J", "join");
        g.link("A", "X"); g.link("X", "B", Map.of("priority", 1, "conditionExpr", "quantity > 10"));
        g.link("X", "C", Map.of("defaultRoute", true));
        g.link("B", "J"); g.link("C", "J"); g.link("J", "End");
        String run = g.start(); complete(run, g, "A", output(12, "kg", "A"));
        assertTrue(forNode(steps(run), g.id("C")).isEmpty());
        complete(run, g, "B", output(2, "kg", "B"));
        assertEquals("completed", one(run, g, "J").path("status").asText());
        assertEquals(1, one(run, g, "J").path("outputSnapshot").path("arrivals").size());
        assertEquals(1, events(run, "join_arrived").size());
    }

    @Test
    void skippingTheOtherBranchRechecksAnAlreadyWaitingJoin() throws Exception {
        Graph g = diamond("skip", null); String run = g.start();
        complete(run, g, "A", Map.of()); complete(run, g, "B", output(2, "kg", "B"));
        fail(run, g, "C");
        assertEquals("skipped", one(run, g, "C").path("status").asText());
        assertEquals("completed", one(run, g, "J").path("status").asText());
        assertEquals(1, events(run, "join_arrived").size());
        assertEquals(1, forNode(steps(run), g.id("End")).size());
    }

    @Test
    void retryingABranchKeepsTheJoinOpenAndDoesNotAddAnArrival() throws Exception {
        Graph g = diamond("retry", null); String run = g.start();
        complete(run, g, "A", Map.of()); complete(run, g, "B", output(2, "kg", "B"));
        fail(run, g, "C");
        assertEquals("running", one(run, g, "C").path("status").asText());
        assertEquals("planned", one(run, g, "J").path("status").asText());
        assertEquals(1, events(run, "join_arrived").size());
        complete(run, g, "C", output(3, "kg", "C"));
        assertEquals("completed", one(run, g, "J").path("status").asText());
        assertEquals(2, events(run, "join_arrived").size());
        assertEquals(2, data(get(path(run, one(run, g, "C").path("stepId").asText(), "attempts"))).size());
    }

    @Test
    void convergingOrdinaryNodesCanDeliverMoreThanOneArrivalOnTheSameConnection() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "D", "E", "End")) g.node(name, "process");
        g.node("J", "join");
        for (String name : List.of("B", "C", "E")) g.link("A", name);
        g.link("B", "D"); g.link("C", "D");
        String shared = g.link("D", "J"); g.link("E", "J"); g.link("J", "End");
        String run = g.start(); complete(run, g, "A", Map.of());
        complete(run, g, "B", Map.of()); complete(run, g, "C", Map.of());
        complete(run, g, "E", output(3, "kg", "E"));
        List<JsonNode> duplicates = forNode(steps(run), g.id("D")); assertEquals(2, duplicates.size());
        completeStep(run, duplicates.get(0), output(1, "kg", "D1"));
        assertEquals("planned", one(run, g, "J").path("status").asText());
        completeStep(run, duplicates.get(1), output(2, "kg", "D2"));
        JsonNode arrivals = one(run, g, "J").path("outputSnapshot").path("arrivals");
        assertEquals(3, arrivals.size());
        assertEquals(2, texts(arrivals, "connectionId").stream().filter(shared::equals).count());
        assertEquals(3, new java.util.HashSet<>(texts(arrivals, "sourceStepId")).size());
        assertEquals(6, one(run, g, "J").path("outputSnapshot").path("quantity").asInt());
    }

    @Test
    void noArrivalsCreateNeitherJoinNorDownstreamWork() throws Exception {
        Graph g = new Graph();
        for (String name : List.of("A", "B", "C", "End")) g.node(name, "process"); g.node("J", "join");
        g.link("A", "B", Map.of("conditionExpr", "quantity > 100"));
        g.link("A", "C", Map.of("conditionExpr", "quantity > 100"));
        g.link("B", "J"); g.link("C", "J"); g.link("J", "End");
        String run = g.start(); complete(run, g, "A", output(0, "kg", "A"));
        assertEquals(1, steps(run).size()); assertEquals(0, events(run, "join_arrived").size());
        assertEquals("finished", data(post("/flow-runs/" + run + "/finish")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).path("status").asText());
    }

    @Test
    void rejectedCompletionRollsBackTheNewArrivalAndCanBeReportedAgain() throws Exception {
        Graph g = diamond(null, 4); String run = g.start();
        complete(run, g, "A", Map.of()); complete(run, g, "B", output(2, "kg", "B"));
        String c = one(run, g, "C").path("stepId").asText(); data(post(path(run, c, "start")));
        report(run, c, output(3, "kg", "C")).andExpect(status().isConflict());
        assertEquals("running", one(run, g, "C").path("status").asText());
        assertEquals("planned", one(run, g, "J").path("status").asText());
        assertEquals(1, events(run, "join_arrived").size());
        assertTrue(forNode(steps(run), g.id("End")).isEmpty());
        accepted(report(run, c, output(1, "kg", "C")));
        assertEquals(3, one(run, g, "J").path("outputSnapshot").path("quantity").asInt());
        assertEquals(2, events(run, "join_arrived").size());
    }

    @Test
    void aSkipThatTriggersAnInvalidJoinTransferFailsTheRunWithoutRollingBackTheSkip() throws Exception {
        Graph g = diamond("skip", 1); String run = g.start();
        complete(run, g, "A", Map.of()); complete(run, g, "B", output(2, "kg", "B"));
        fail(run, g, "C");
        assertEquals("skipped", one(run, g, "C").path("status").asText());
        assertEquals("failed", one(run, g, "J").path("status").asText());
        assertEquals("JOIN_TRANSFER_REJECTED", one(run, g, "J").path("errorCode").asText());
        assertEquals("failed", data(get("/flow-runs/" + run)).path("status").asText());
        assertTrue(forNode(steps(run), g.id("End")).isEmpty());
    }

    @Test
    void simultaneousCompletionsAndADuplicateReportStillCreateOneJoin() throws Exception {
        Graph g = diamond(null, null); String run = g.start(); complete(run, g, "A", Map.of());
        String b = one(run, g, "B").path("stepId").asText(), c = one(run, g, "C").path("stepId").asText();
        data(post(path(run, b, "start"))); data(post(path(run, c, "start")));
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        try {
            var left = pool.submit(() -> { ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return report(run, b, output(2, "kg", "B")).andReturn().getResponse().getStatus(); });
            var right = pool.submit(() -> { ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return report(run, c, output(3, "kg", "C")).andReturn().getResponse().getStatus(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); release.countDown();
            assertEquals(200, left.get(20, TimeUnit.SECONDS)); assertEquals(200, right.get(20, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, forNode(steps(run), g.id("J")).size());
        assertEquals(1, forNode(steps(run), g.id("End")).size());
        assertEquals(2, events(run, "join_arrived").size());
        report(run, b, output(2, "kg", "B")).andExpect(status().isConflict());
        assertEquals(2, events(run, "join_arrived").size());
    }

    @Test
    void mixedUnitsStayInIndividualArrivalsWithoutAnInventedTotal() throws Exception {
        Graph g = diamond(null, null); String run = g.start(); complete(run, g, "A", Map.of());
        complete(run, g, "B", output(2, "kg", "B")); complete(run, g, "C", output(3, "ea", "C"));
        JsonNode output = one(run, g, "J").path("outputSnapshot");
        assertFalse(output.hasNonNull("quantity")); assertFalse(output.hasNonNull("unit"));
        assertEquals(List.of("kg", "ea"), texts(output.path("arrivals"), "unit"));
        assertEquals(List.of("B", "C"), texts(output.path("arrivals"), "attrs", "tag"));
    }

    @Test
    void publishingChecksJoinInputs() throws Exception {
        Graph missing = new Graph(); missing.node("J", "join"); missing.node("End", "process"); missing.link("J", "End");
        JsonNode invalid = data(get("/workflows/" + missing.workflow + "/validation"));
        assertTrue(texts(invalid.path("issues"), "code").contains("JOIN_WITHOUT_INPUT"));
        call(post("/workflows/" + missing.workflow + "/revisions")).andExpect(status().isConflict());
        Graph single = new Graph(); single.node("A", "process"); single.node("J", "join"); single.node("End", "process");
        single.link("A", "J"); single.link("J", "End");
        assertTrue(texts(data(get("/workflows/" + single.workflow + "/validation")).path("issues"), "code")
            .contains("JOIN_SINGLE_INPUT"));
    }

    @Test
    void ordinaryConvergenceStillCreatesSeparateStepsInAnOlderRevision() throws Exception {
        Graph g = new Graph(); for (String name : List.of("A", "B", "C", "D")) g.node(name, "process");
        g.link("A", "B"); g.link("A", "C"); g.link("B", "D"); g.link("C", "D");
        String run = g.start(); complete(run, g, "A", Map.of());
        complete(run, g, "B", output(2, "kg", "B")); complete(run, g, "C", output(3, "kg", "C"));
        List<JsonNode> separate = forNode(steps(run), g.id("D")); assertEquals(2, separate.size());
        assertEquals(java.util.Set.of(2, 3), separate.stream().map(s -> s.path("inputSnapshot").path("quantity").asInt())
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, events(run, "join_arrived").size());
    }

    @Test
    void largeFractionRetainsItsValueInConditionAndReceivedSnapshot() throws Exception {
        Graph g = new Graph(); g.node("A", "process"); g.node("B", "process");
        g.link("A", "B", Map.of("conditionExpr", "quantity = 999999999999999.9999"));
        String run = g.start();
        complete(run, g, "A", Map.of("quantity", new java.math.BigDecimal("999999999999999.9999"), "unit", "kg"));
        assertEquals(1, forNode(steps(run), g.id("B")).size(), "The exact quantity must match the condition");
        assertEquals(0, new java.math.BigDecimal("999999999999999.9999").compareTo(
            one(run, g, "B").path("inputSnapshot").path("quantity").decimalValue()));
    }

    @Test
    void largeFractionAndSmallArrivalRetainTheExactJoinedTotal() throws Exception {
        Graph g = diamond(null, null); String run = g.start();
        complete(run, g, "A", Map.of());
        complete(run, g, "B", Map.of("quantity", new java.math.BigDecimal("999999999999999.9998"), "unit", "kg"));
        complete(run, g, "C", Map.of("quantity", new java.math.BigDecimal("0.0001"), "unit", "kg"));
        assertEquals(0, new java.math.BigDecimal("999999999999999.9999").compareTo(
            one(run, g, "J").path("outputSnapshot").path("quantity").decimalValue()));
    }

    private Graph diamond(String cPolicy, Integer capacity) throws Exception {
        Graph g = new Graph(); for (String name : List.of("A", "B", "C", "End")) g.node(name, "process"); g.node("J", "join");
        g.link("A", "B"); g.link("A", "C", cPolicy == null ? Map.of() : Map.of("failurePolicy", cPolicy));
        g.link("B", "J"); g.link("C", "J"); g.link("J", "End", capacity == null ? Map.of() : Map.of("capacity", capacity));
        return g;
    }
    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();
        Graph() throws Exception {
            String project = data(post("/projects").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("projectName", "Join acceptance " + UUID.randomUUID(), "ownerId", DEMO_OWNER)))).path("projectId").asText();
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("projectId", project, "workflowName", "Routing")))).path("workflowId").asText();
        }
        String id(String name) { return nodes.get(name); }
        void node(String name, String type) throws Exception {
            nodes.put(name, data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("workflowId", workflow, "processName", name, "nodeType", type)))).path("processId").asText());
        }
        String link(String from, String to) throws Exception { return link(from, to, Map.of()); }
        String link(String from, String to, Map<String, ?> options) throws Exception {
            Map<String, Object> body = new HashMap<>(options); body.put("workflowId", workflow);
            body.put("fromProcessId", id(from)); body.put("toProcessId", id(to));
            return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .path("connectionId").asText();
        }
        String start() throws Exception {
            String revision = data(post("/workflows/" + workflow + "/revisions")).path("workflowRevisionId").asText();
            return data(post("/flow-runs/graph").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("workflowId", workflow, "workflowRevisionId", revision, "runType", "test")))).path("flowRunId").asText();
        }
    }
    private void complete(String run, Graph g, String node, Map<String, ?> output) throws Exception { completeStep(run, one(run, g, node), output); }
    private void completeStep(String run, JsonNode step, Map<String, ?> output) throws Exception {
        String id = step.path("stepId").asText();
        if ("planned".equals(step.path("status").asText())) data(post(path(run, id, "start")));
        accepted(report(run, id, output));
    }
    private void fail(String run, Graph g, String node) throws Exception {
        String id = one(run, g, node).path("stepId").asText(); data(post(path(run, id, "start")));
        data(post(path(run, id, "fail")).contentType(MediaType.APPLICATION_JSON).content("{\"errorCode\":\"CHECK\",\"errorMessage\":\"Test failure\"}"));
    }
    private ResultActions report(String run, String step, Map<String, ?> output) throws Exception {
        return call(post(path(run, step, "complete")).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("outputSnapshot", output))));
    }
    private static Map<String, ?> output(int quantity, String unit, String tag) { return Map.of("quantity", quantity, "unit", unit, "attrs", Map.of("tag", tag)); }
    private static String path(String run, String step, String action) { return "/flow-runs/" + run + "/steps/" + step + "/" + action; }
    private JsonNode steps(String run) throws Exception { return data(get("/flow-runs/" + run + "/steps")); }
    private JsonNode one(String run, Graph g, String name) throws Exception {
        List<JsonNode> found = forNode(steps(run), g.id(name)); assertEquals(1, found.size(), "Expected one step for " + name); return found.get(0);
    }
    private static List<JsonNode> forNode(JsonNode steps, String node) {
        List<JsonNode> result = new ArrayList<>(); steps.forEach(s -> { if (node.equals(s.path("nodeId").asText())) result.add(s); }); return result;
    }
    private List<JsonNode> events(String run, String type) throws Exception {
        List<JsonNode> found = new ArrayList<>(); data(get("/flow-runs/" + run + "/events")).forEach(e -> {
            if (type.equals(e.path("eventType").asText())) found.add(e); }); return found;
    }
    private static List<String> texts(JsonNode array, String... fields) {
        List<String> result = new ArrayList<>(); array.forEach(row -> { JsonNode value = row;
            for (String field : fields) value = value.path(field); result.add(value.asText()); }); return result;
    }
    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER)));
    }
    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception { return accepted(call(request)); }
    private JsonNode accepted(ResultActions response) throws Exception {
        var http = response.andReturn().getResponse();
        JsonNode envelope = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(http.getContentAsString());
        assertEquals(200, http.getStatus(), envelope.path("message").asText());
        return envelope.path("data");
    }
}
