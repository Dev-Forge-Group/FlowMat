package org.myweb.flowmat.flowkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.flowkernel.FlowKernelFixtures.MAPPER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The flow kernel's walks (docs/architecture/adr/ADR-040-flow-kernel.md decision 7, "try it"): each graph published as a
 * revision and run by the server as a graph run with one input, every step a person would report completed with what it
 * received, until nothing more can go on, then finished when every step is done. What the server did is the answer the
 * kernel's walk must give: the run's status (finished, failed, or running where a report was refused or a step waits),
 * each step's
 * node, status, input, output and source, each refused report, the run's failure, and the losses, filtered connections
 * and gateway routes it recorded. Delays are left out of the graphs: they change when a step may start, not what goes
 * where, and routing.json covers them. After a deliberate change, rewrite with
 * {@code ./gradlew test --tests '*FlowKernel*' -Dflow.fixtures.update=true} and read the diff.
 */
@AutoConfigureMockMvc
class FlowKernelWalkFixtureTest extends IntegrationTestSupport {

    private static final String FILE = "walk.json";
    private static final long SEED = 20261125L;
    private static final Set<String> EVENTS = Set.of("transfer_loss", "connection_filtered", "gateway_routed");
    private static final String[] INPUTS = {
        "{\"quantity\":10,\"unit\":\"kg\",\"attrs\":{\"lot\":\"A\",\"grade\":3}}",
        "{\"quantity\":12.34567,\"unit\":\"kg\",\"item\":\"item-a\",\"attrs\":{\"lot\":\"B\",\"grade\":4,\"ok\":true}}",
        "{\"quantity\":1,\"unit\":\"g\",\"attrs\":{\"lot\":\"A\",\"grade\":1}}",
        "{\"quantity\":100,\"attrs\":{\"grade\":5,\"ok\":false}}",
        "{\"quantity\":0.0001,\"unit\":\"kg\",\"attrs\":{\"lot\":\"A\"}}",
        "{\"unit\":\"kg\",\"attrs\":{\"lot\":\"A\",\"grade\":2}}",
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void theServersRunsAreTheFixtures() throws Exception {
        Map<String, ObjectNode> cases = new LinkedHashMap<>();
        if (FlowKernelFixtures.UPDATE) {
            for (Map.Entry<String, JsonNode> named : candidates().entrySet()) {
                Random random = new Random(SEED ^ named.getKey().hashCode());
                ObjectNode entry = MAPPER.createObjectNode();
                entry.set("graph", withoutDelays(named.getValue()));
                entry.set("input", MAPPER.readTree(INPUTS[random.nextInt(INPUTS.length)]));
                cases.put(named.getKey(), entry);
            }
        } else {
            FlowKernelFixtures.read(FILE).get("cases").forEach(entry -> {
                ObjectNode input = MAPPER.createObjectNode();
                input.set("graph", entry.get("graph"));
                input.set("input", entry.get("input"));
                cases.put(entry.get("id").asText(), input);
            });
        }
        String projectId = data(post("/projects"), MAPPER.createObjectNode()
            .put("projectName", "Flow kernel walks " + UUID.randomUUID()).put("ownerId", DEMO_OWNER)).path("projectId").asText();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (Map.Entry<String, ObjectNode> named : cases.entrySet()) {
            ObjectNode entry = named.getValue();
            ObjectNode expected = walk(projectId, named.getKey(), entry.get("graph"), entry.get("input"));
            entry.set("expected", expected);
            outcomes.merge(expected.path("status").asText(), 1, Integer::sum);
            expected.get("steps").forEach(step -> outcomes.merge("step_" + step.path("status").asText(), 1, Integer::sum));
            expected.get("events").forEach(event -> outcomes.merge(event.path("type").asText(), 1, Integer::sum));
        }
        ObjectNode header = MAPPER.createObjectNode();
        header.put("seed", SEED);
        header.putObject("context").put("schemaVersion", 3);
        if (FlowKernelFixtures.UPDATE) System.out.printf("%s: %d walks, outcomes %s%n", FILE, cases.size(), outcomes);
        FlowKernelFixtures.writeOrCheck(FILE, header, cases);
        assertThat(outcomes).as("walks that finish, fail and stop, with losses, filters and routes")
            .containsKeys("finished", "failed", "running", "step_completed", "step_refused", "transfer_loss",
                "connection_filtered", "gateway_routed");
    }

    /** The graphs routing.json reads as a revision that can run, and the hand-picked ones. */
    private static Map<String, JsonNode> candidates() {
        Map<String, JsonNode> graphs = new LinkedHashMap<>();
        FlowKernelFixtures.read("routing.json").get("cases").forEach(entry -> {
            if (!entry.get("expected").has("error")) graphs.put(entry.get("id").asText(), entry.get("graph"));
        });
        return graphs;
    }

    private static JsonNode withoutDelays(JsonNode graph) {
        ObjectNode copy = graph.deepCopy();
        copy.get("connections").forEach(connection -> {
            ((ObjectNode) connection).remove("delayTimeSec");
            ((ObjectNode) connection).remove("delayBasis");
        });
        return copy;
    }

    /**
     * Publishes the graph as a revision of a new workflow, starts a graph run with the input and completes, in step order,
     * each planned step a person reports (not a Join, which the server passes) with the input it received. A report the
     * server refuses stays running; a run that fails ends the walk.
     */
    private ObjectNode walk(String projectId, String id, JsonNode graph, JsonNode input) throws Exception {
        String workflowId = data(post("/workflows"), MAPPER.createObjectNode().put("projectId", projectId)
            .put("workflowName", "Walk " + id)).path("workflowId").asText();
        String revisionId = UUID.randomUUID().toString();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("schemaVersion", 3);
        snapshot.set("processes", graph.get("processes"));
        snapshot.set("processIos", graph.get("ports"));
        snapshot.set("connections", graph.get("connections"));
        jdbcTemplate.update("insert into workflow_revision (workflow_revision_id, workflow_id, revision_no, status,"
                + " schema_version, snapshot_json, published_by, published_at) values (?, ?, 1, 'published', 3, ?::jsonb, ?, ?)",
            revisionId, workflowId, MAPPER.writeValueAsString(snapshot), DEMO_OWNER, OffsetDateTime.now(ZoneOffset.UTC));
        ObjectNode start = MAPPER.createObjectNode().put("workflowId", workflowId).put("workflowRevisionId", revisionId)
            .put("runType", "test");
        start.set("inputPayload", input);
        String runId = data(post("/flow-runs/graph"), start).path("flowRunId").asText();

        Set<String> joins = new HashSet<>();
        graph.get("processes").forEach(process -> {
            if ("join".equals(process.path("nodeType").asText().toLowerCase(Locale.ROOT))) {
                joins.add(process.path("processId").asText());
            }
        });
        Map<String, ObjectNode> refused = new HashMap<>();
        while ("running".equals(data(get("/flow-runs/" + runId), null).path("status").asText())) {
            JsonNode next = null;
            for (JsonNode step : data(get("/flow-runs/" + runId + "/steps"), null)) {
                if ("planned".equals(step.path("status").asText()) && !joins.contains(step.path("nodeId").asText())) {
                    next = step;
                    break;
                }
            }
            if (next == null) break;
            String stepId = next.path("stepId").asText();
            data(post("/flow-runs/" + runId + "/steps/" + stepId + "/start"), null);
            ObjectNode report = MAPPER.createObjectNode();
            report.set("outputSnapshot", next.get("inputSnapshot"));
            MockHttpServletResponse completed = call(post("/flow-runs/" + runId + "/steps/" + stepId + "/complete"), report);
            if (completed.getStatus() != 200) {
                JsonNode body = MAPPER.readTree(completed.getContentAsString());
                refused.put(stepId, MAPPER.createObjectNode().put("status", completed.getStatus())
                    .put("message", body.path("message").asText()));
            }
        }

        // A run whose every step is done is finished, as a person would; one with a refused report or a step left waits.
        JsonNode steps = data(get("/flow-runs/" + runId + "/steps"), null);
        boolean done = true;
        for (JsonNode step : steps) {
            done &= Set.of("completed", "skipped").contains(step.path("status").asText());
        }
        if (done && "running".equals(data(get("/flow-runs/" + runId), null).path("status").asText())) {
            data(post("/flow-runs/" + runId + "/finish"), MAPPER.createObjectNode());
        }
        JsonNode run = data(get("/flow-runs/" + runId), null);
        Map<String, Integer> index = new HashMap<>();
        for (JsonNode step : steps) index.put(step.path("stepId").asText(), index.size());
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("status", run.path("status").asText());
        JsonNode recorded = data(get("/flow-runs/" + runId + "/events"), null);
        for (JsonNode event : recorded) {
            // The run's own failure is in its run_failed event: the run response does not carry it.
            if ("run_failed".equals(event.path("eventType").asText())) {
                answer.putObject("failure").put("code", event.path("payload").path("errorCode").asText())
                    .put("message", event.path("payload").path("errorMessage").asText());
            }
        }
        ArrayNode answerSteps = answer.putArray("steps");
        for (JsonNode step : steps) {
            ObjectNode out = answerSteps.addObject();
            out.put("nodeId", step.path("nodeId").asText());
            ObjectNode refusal = refused.get(step.path("stepId").asText());
            out.put("status", refusal != null ? "refused" : step.path("status").asText());
            if (step.hasNonNull("sourceStepId")) out.put("source", index.get(step.path("sourceStepId").asText()));
            if (step.hasNonNull("sourceConnectionId")) out.put("connectionId", step.path("sourceConnectionId").asText());
            out.set("input", byIndex(step.get("inputSnapshot"), index));
            if (step.hasNonNull("outputSnapshot")) out.set("output", byIndex(step.get("outputSnapshot"), index));
            if (step.hasNonNull("errorCode")) {
                out.putObject("error").put("code", step.path("errorCode").asText())
                    .put("message", step.path("errorMessage").asText());
            }
            if (refusal != null) out.set("refusal", refusal);
        }
        List<ObjectNode> events = new ArrayList<>();
        for (JsonNode event : recorded) {
            if (!EVENTS.contains(event.path("eventType").asText())) continue;
            ObjectNode out = MAPPER.createObjectNode();
            out.put("step", index.get(event.path("stepId").asText()));
            out.put("type", event.path("eventType").asText());
            out.set("payload", event.get("payload"));
            events.add(out);
        }
        // Events of one completion share a time; their order there is not part of the answer.
        events.sort(Comparator.comparingInt((ObjectNode event) -> event.path("step").asInt())
            .thenComparing(event -> event.path("type").asText())
            .thenComparing(event -> event.path("payload").path("connectionId").asText()));
        answer.putArray("events").addAll(events);
        return answer;
    }

    /** A Join's arrivals name their source steps by id; the answer names steps by their place in the run instead. */
    private static JsonNode byIndex(JsonNode snapshot, Map<String, Integer> index) {
        if (snapshot == null) return null;
        JsonNode copy = snapshot.deepCopy();
        List<JsonNode> pending = new ArrayList<>(List.of(copy));
        while (!pending.isEmpty()) {
            JsonNode node = pending.remove(pending.size() - 1);
            if (node instanceof ObjectNode object) {
                JsonNode source = object.get("sourceStepId");
                if (source != null && source.isTextual() && index.containsKey(source.asText())) {
                    object.put("sourceStepId", index.get(source.asText()));
                }
            }
            node.forEach(pending::add);
        }
        return copy;
    }

    private JsonNode data(MockHttpServletRequestBuilder request, JsonNode body) throws Exception {
        MockHttpServletResponse response = call(request, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return MAPPER.readTree(response.getContentAsString()).path("data");
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, JsonNode body) throws Exception {
        request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
