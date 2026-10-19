package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** LB13-LB18 step identity and physical-run aggregation; no BOM or inventory fixtures. */
@AutoConfigureMockMvc
class RunLaborStepBoundaryRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"other-run", "gateway", "long", "nul"})
    void liveRecordsRejectInvalidStepIdsWithoutChangingStoredState(String kind) throws Exception {
        Fixture f = new Fixture(true);
        String run = f.start("simulation", null);
        String invalid = f.invalidStep(kind, run);
        var before = stored(run);
        JsonNode error = refused(post(labor(run)), worked(invalid, 5));
        assertTrue(error.path("message").asText().contains("stepId"));
        assertEquals(before, stored(run));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-run", "gateway", "long", "nul"})
    void finishedRunCorrectionsRejectInvalidStepIdsWithoutLeavingACorrection(String kind) throws Exception {
        Fixture f = new Fixture(true);
        String run = f.start("simulation", null);
        String invalid = f.invalidStep(kind, run);
        data(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1));
        var before = stored(run);
        JsonNode error = refused(post(corrections(run)), Map.of("reason", "Count on this step", "lines",
            List.of(Map.of("kind", "add_labor", "userId", DEMO_OWNER, "workedMinutes", 5, "stepId", invalid))));
        assertTrue(error.path("message").asText().contains("stepId"));
        assertEquals(before, stored(run));
        assertEquals(0, data(get(corrections(run)), null).size());
    }

    @Test
    void aRunWithoutAGraphHasNoStepsAndCannotRecordTimeOnOne() throws Exception {
        Fixture f = new Fixture(false);
        String run = f.start("simulation", null);
        assertEquals(0, data(get(labor(run)), null).path("steps").size());
        var before = stored(run);
        refused(post(labor(run)), worked("missing", 5));
        assertEquals(before, stored(run));
        data(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1));
        before = stored(run);
        refused(post(corrections(run)), Map.of("reason", "Not a graph", "lines",
            List.of(Map.of("kind", "add_labor", "userId", DEMO_OWNER, "workedMinutes", 5, "stepId", "missing"))));
        assertEquals(before, stored(run));
        assertEquals(0, data(get(labor(run)), null).path("steps").size());
    }

    @Test
    void actualRunsShareAProcessTotalAcrossRevisionsAndExcludeSimulationAndCancelledTime() throws Exception {
        Fixture f = new Fixture(true);
        String order = data(post("/work-orders"), Map.of("projectId", f.project, "workflowId", f.workflow,
            "workOrderTitle", "Process totals", "targetQuantity", 10)).path("workOrderId").asText();
        data(post("/work-orders/" + order + "/approve"), null);
        String first = f.start("actual", order);
        f.record(first, f.step(first, "Work"), 20);
        f.record(first, null, 5);
        f.revision = data(post("/workflows/" + f.workflow + "/revisions"), null).path("workflowRevisionId").asText();
        String second = f.start("actual", order);
        f.record(second, f.step(second, "Work"), 30);
        f.record(second, null, 7);
        JsonNode recorded = f.record(second, f.step(second, "Work"), 11);
        String cancelled = "";
        for (JsonNode line : recorded.path("lines")) {
            if (line.path("workedMinutes").asInt() == 11) cancelled = line.path("runLaborId").asText();
        }
        assertTrue(!cancelled.isBlank());
        data(post(labor(second) + "/" + cancelled + "/cancel"), Map.of("reason", "Duplicate measurement"));
        String simulation = f.start("simulation", order);
        f.record(simulation, f.step(simulation, "Work"), 500);
        f.record(simulation, null, 400);

        JsonNode comparison = data(get("/work-orders/" + order + "/labor-comparison"), null);
        assertEquals(2, comparison.path("runs").asInt());
        assertEquals(62, comparison.path("actualMinutes").asInt());
        assertEquals(12, comparison.path("wholeRunMinutes").asInt());
        assertEquals(1, comparison.path("steps").size());
        assertEquals(f.work, comparison.path("steps").get(0).path("nodeId").asText());
        assertEquals(50, comparison.path("steps").get(0).path("actualMinutes").asInt());
        assertEquals(1, comparison.path("people").size());
        assertEquals(62, comparison.path("people").get(0).path("actualMinutes").asInt());
        assertEquals(900, data(get(labor(simulation)), null).path("workedMinutes").asInt());
        assertEquals(3, data(get(labor(second)), null).path("lines").size());
    }

    private final class Fixture {
        final String project;
        final String workflow;
        String work;
        String revision;

        Fixture(boolean graph) throws Exception {
            project = data(post("/projects"), Map.of("projectName", "Labor step boundaries " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            workflow = data(post("/workflows"), Map.of("projectId", project, "workflowName", "Step boundaries"))
                .path("workflowId").asText();
            if (graph) {
                work = node("Work", "process");
                String split = node("Split", "split");
                String left = node("Left", "process");
                String right = node("Right", "process");
                data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", work, "toProcessId", split));
                for (String target : List.of(left, right)) data(post("/process-connections"), Map.of(
                    "workflowId", workflow, "fromProcessId", split, "toProcessId", target, "splitKind", "share", "splitValue", 50));
                revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
            }
        }

        String node(String name, String type) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", type))
                .path("processId").asText();
        }

        String start(String type, String order) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("projectId", project, "workflowId", workflow,
                "runType", type, "plannedOutputQty", 1));
            if (revision != null) body.put("workflowRevisionId", revision);
            if (order != null) body.put("workOrderId", order);
            JsonNode started = data(post("/production-runs/start"), body);
            assertTrue(started.path("bomId").isNull());
            String run = started.path("productionRunId").asText();
            if (revision != null) {
                String first = step(run, "Work");
                data(post("/production-runs/" + run + "/steps/" + first + "/start"), null);
                data(post("/production-runs/" + run + "/steps/" + first + "/complete"),
                    Map.of("outputSnapshot", Map.of("quantity", 100, "unit", "kg")));
            }
            return run;
        }

        String step(String run, String name) throws Exception {
            for (JsonNode step : data(get("/production-runs/" + run + "/steps"), null)) {
                if (name.equals(step.path("nodeName").asText())) return step.path("stepId").asText();
            }
            throw new AssertionError("Missing step " + name);
        }

        String invalidStep(String kind, String run) throws Exception {
            return switch (kind) {
                case "other-run" -> step(start("simulation", null), "Work");
                case "gateway" -> step(run, "Split");
                case "long" -> "a".repeat(51);
                case "nul" -> "\u0000";
                default -> throw new IllegalArgumentException(kind);
            };
        }

        JsonNode record(String run, String step, int minutes) throws Exception {
            return data(post(labor(run)), worked(step, minutes));
        }
    }

    private Map<String, Object> worked(String step, int minutes) {
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "workedMinutes", minutes));
        if (step != null) body.put("stepId", step);
        return body;
    }

    private Map<String, List<Map<String, Object>>> stored(String run) {
        return Map.of("run", jdbc.queryForList("select * from production_run where production_run_id=?", run),
            "labor", jdbc.queryForList("select * from production_run_labor where production_run_id=? order by run_labor_id", run),
            "corrections", jdbc.queryForList("select * from production_run_correction where production_run_id=? "
                + "order by production_run_correction_id", run),
            "lines", jdbc.queryForList("select l.* from production_run_correction_line l join production_run_correction c "
                + "on l.production_run_correction_id=c.production_run_correction_id where c.production_run_id=? "
                + "order by l.production_run_correction_line_id", run));
    }

    private static String labor(String run) { return "/production-runs/" + run + "/labor"; }
    private static String corrections(String run) { return "/production-runs/" + run + "/corrections"; }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode refused(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(request, body).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
