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
import java.time.Duration;
import java.time.OffsetDateTime;
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

/** Connection delays in graph runs (docs/architecture/adr/ADR-010-connection-delay.md decisions 1-10). */
@AutoConfigureMockMvc
class ConnectionDelayIntegrationTest extends IntegrationTestSupport {
    private static final String RANGE = "delayTimeSec is 0 or more seconds.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aStepAfterADelayIsScheduledThenAndCannotStartSooner() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        String ab = g.link("A", "B", Map.of("delayTimeSec", 7200));
        JsonNode revision = g.publish();
        assertEquals(3, revision.path("schemaVersion").asInt());
        String run = g.start(revision.path("workflowRevisionId").asText());
        complete(run, g, "A", output(100));
        JsonNode a = one(run, g, "A");
        JsonNode b = one(run, g, "B");
        assertAfter(Duration.ofHours(2), a.path("endedAt"), b.path("scheduledAt"));
        call(post(step(run, b.path("stepId").asText(), "start"))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Step cannot start before its scheduledAt."));
        JsonNode scheduled = event(run, b.path("stepId").asText(), "step_scheduled").path("payload");
        assertEquals("connection_delay", scheduled.path("reason").asText());
        assertEquals(ab, scheduled.path("connectionId").asText());
        assertEquals(0, scheduled.path("delaySeconds").decimalValue().compareTo(java.math.BigDecimal.valueOf(7200)));
        assertEquals(time(b.path("scheduledAt")), OffsetDateTime.parse(scheduled.path("scheduledAt").asText()).toInstant());
    }

    @Test
    void gatewaysPassAtOnceButCarryTheTimeOnAndAJoinTakesTheLatest() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("C", "process");
        g.node("X", "exclusive");
        g.link("A", "X", Map.of("delayTimeSec", 1800));
        g.link("X", "C", Map.of("delayTimeSec", 3600));
        String run = g.start(g.publish().path("workflowRevisionId").asText());
        complete(run, g, "A", output(100));
        JsonNode a = one(run, g, "A");
        JsonNode x = one(run, g, "X");
        assertEquals("completed", x.path("status").asText());
        assertAfter(Duration.ofMinutes(30), a.path("endedAt"), x.path("scheduledAt"));
        assertAfter(Duration.ofMinutes(90), a.path("endedAt"), one(run, g, "C").path("scheduledAt"));

        Graph joined = new Graph();
        for (String name : List.of("A", "B", "D", "E")) joined.node(name, "process");
        joined.node("J", "join");
        joined.link("A", "B");
        joined.link("A", "D");
        joined.link("B", "J");
        joined.link("D", "J", Map.of("delayTimeSec", 600));
        joined.link("J", "E");
        String second = joined.start(joined.publish().path("workflowRevisionId").asText());
        complete(second, joined, "A", output(10));
        assertTrue(one(second, joined, "B").path("scheduledAt").isNull());
        complete(second, joined, "B", output(4));
        complete(second, joined, "D", output(6));
        JsonNode d = one(second, joined, "D");
        JsonNode j = one(second, joined, "J");
        assertEquals("completed", j.path("status").asText());
        assertAfter(Duration.ofMinutes(10), d.path("endedAt"), j.path("scheduledAt"));
        JsonNode e = one(second, joined, "E");
        assertEquals(time(j.path("scheduledAt")), time(e.path("scheduledAt")));
        call(post(step(second, e.path("stepId").asText(), "start"))).andExpect(status().isConflict());
    }

    @Test
    void theDelayIsAFloorForRescheduling() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        g.link("A", "B", Map.of("delayTimeSec", 7200));
        String run = g.start(g.publish().path("workflowRevisionId").asText());
        complete(run, g, "A", output(100));
        JsonNode b = one(run, g, "B");
        String id = b.path("stepId").asText();
        OffsetDateTime ready = OffsetDateTime.parse(b.path("scheduledAt").asText());
        schedule(run, id, "\"" + ready.minusHours(1) + "\"").andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("schedule it at or after then.")));
        schedule(run, id, "null").andExpect(status().isConflict());
        JsonNode later = accepted(schedule(run, id, "\"" + ready.plusHours(1) + "\""));
        assertEquals(ready.plusHours(1).toInstant(), time(later.path("scheduledAt")));
    }

    @Test
    void aRevisionPublishedBeforeTheDelayCountedRunsAsBefore() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        g.link("A", "B", Map.of("delayTimeSec", 7200));
        String revision = g.publish().path("workflowRevisionId").asText();
        jdbc.update("UPDATE workflow_revision SET schema_version = 2, snapshot_json = jsonb_set(snapshot_json, '{schemaVersion}', '2')"
            + " WHERE workflow_revision_id = ?", revision);
        String run = g.start(revision);
        complete(run, g, "A", output(100));
        JsonNode b = one(run, g, "B");
        assertTrue(b.path("scheduledAt").isNull());
        assertTrue(events(run, "step_scheduled").isEmpty());
        data(post(step(run, b.path("stepId").asText(), "start")));
    }

    @Test
    void delaysAreZeroOrMoreWhenSavedAndWhenPublished() throws Exception {
        Graph g = new Graph();
        g.node("A", "process");
        g.node("B", "process");
        Map<String, Object> body = new HashMap<>(Map.of("workflowId", g.workflow, "fromProcessId", g.id("A"),
            "toProcessId", g.id("B"), "delayTimeSec", -1));
        call(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RANGE));
        String ab = g.link("A", "B", Map.of("delayTimeSec", 0.5));
        call(put("/process-connections/" + ab).contentType(MediaType.APPLICATION_JSON).content("{\"delayTimeSec\":-1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(RANGE));

        // A value saved before the rule: publishing refuses it.
        jdbc.update("UPDATE process_connection SET delay_time_sec = -5 WHERE connection_id = ?", ab);
        JsonNode issue = null;
        for (JsonNode one : data(get("/workflows/" + g.workflow + "/validation")).path("issues")) {
            if ("DELAY_RANGE".equals(one.path("code").asText())) issue = one;
        }
        assertTrue(issue != null, "DELAY_RANGE");
        assertEquals("error", issue.path("severity").asText());
        assertEquals(ab, issue.path("connectionId").asText());
        call(post("/workflows/" + g.workflow + "/revisions")).andExpect(status().isConflict());
    }

    private ResultActions schedule(String run, String stepId, String value) throws Exception {
        return call(put(step(run, stepId, "schedule")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduledAt\":" + value + "}"));
    }

    private static java.time.Instant time(JsonNode value) {
        assertTrue(value.isTextual(), "time " + value);
        return OffsetDateTime.parse(value.asText()).toInstant();
    }

    /** {@code later} is {@code after} {@code earlier}, to the millisecond (times are kept to the microsecond). */
    private static void assertAfter(Duration after, JsonNode earlier, JsonNode later) {
        long millis = Duration.between(time(earlier), time(later)).minus(after).toMillis();
        assertTrue(Math.abs(millis) <= 1, earlier + " + " + after + " vs " + later);
    }

    /** A workflow in a project of its own, so nothing else's runs or names get in the way. */
    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();

        Graph() throws Exception {
            String project = data(post("/projects").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectName", "Delay " + UUID.randomUUID(), "ownerId", DEMO_OWNER)))).path("projectId").asText();
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectId", project, "workflowName", "Delay")))).path("workflowId").asText();
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
        accepted(call(post(step(run, id, "complete")).contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("outputSnapshot", output)))));
    }

    private static Map<String, ?> output(int quantity) {
        return Map.of("quantity", quantity, "unit", "kg");
    }

    private static String step(String run, String stepId, String action) {
        return "/flow-runs/" + run + "/steps/" + stepId + "/" + action;
    }

    private JsonNode one(String run, Graph g, String name) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        data(get("/flow-runs/" + run + "/steps")).forEach(one -> {
            if (g.id(name).equals(one.path("nodeId").asText())) found.add(one);
        });
        assertEquals(1, found.size(), "one step for " + name);
        return found.get(0);
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
