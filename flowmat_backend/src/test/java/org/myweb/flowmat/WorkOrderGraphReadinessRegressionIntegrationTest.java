package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Readiness uses the published graph and warns without changing the legacy no-steps fallback (2fd). */
@AutoConfigureMockMvc
class WorkOrderGraphReadinessRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"empty", "cycle", "duplicate", "missing-process", "capacity", "condition", "policy"})
    void graphWarningsMatchTheActualRunFallbackWithoutBlockingOrWritingDuringRead(String defect) throws Exception {
        Fixture f = new Fixture(true);
        f.corruptHistoricalGraph(f.revision, defect);
        var before = f.stored();
        JsonNode readiness = f.readiness(DEMO_OWNER);
        assertEquals(before, f.stored(), "Readiness must not start runs or change the revision or work order.");
        JsonNode warning = onlyStepWarning(readiness);
        assertEquals("warn", warning.path("status").asText());
        assertTrue(readiness.path("ready").asBoolean(), readiness.toString());
        String run = f.start();
        assertEquals(0, data(DEMO_OWNER, get("/production-runs/" + run + "/steps"), null).size());
        String reason = jdbc.queryForObject("select payload_json->>'reason' from flow_run_event e "
            + "join flow_run r on r.flow_run_id=e.flow_run_id where r.production_run_id=? "
            + "and e.event_type='steps_unavailable'", String.class, run);
        assertEquals("Runs will start without process steps: " + reason, warning.path("message").asText());
    }

    @Test
    void aRunnablePublishedGraphHasNoWarningAndActuallyCreatesSteps() throws Exception {
        Fixture f = new Fixture(true);
        assertTrue(stepWarnings(f.readiness(DEMO_OWNER)).isEmpty());
        String run = f.start();
        JsonNode steps = data(DEMO_OWNER, get("/production-runs/" + run + "/steps"), null);
        assertEquals(1, steps.size());
        assertEquals(f.first, steps.get(0).path("nodeId").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from flow_run_event e join flow_run r "
            + "on r.flow_run_id=e.flow_run_id where r.production_run_id=? and e.event_type='steps_unavailable'",
            Integer.class, run));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyTheLatestPublishedRevisionDeterminesTheWarning(boolean latestIsValid) throws Exception {
        Fixture f = new Fixture(true);
        if (latestIsValid) f.corruptHistoricalGraph(f.revision, "empty");
        String latest = f.publish();
        if (!latestIsValid) f.corruptHistoricalGraph(latest, "empty");
        JsonNode readiness = f.readiness(DEMO_OWNER);
        assertEquals(latestIsValid, stepWarnings(readiness).isEmpty());
        String run = f.start();
        assertEquals(latest, jdbc.queryForObject("select workflow_revision_id from production_run where production_run_id=?",
            String.class, run));
        assertEquals(latestIsValid ? 1 : 0, data(DEMO_OWNER, get("/production-runs/" + run + "/steps"), null).size());
    }

    @Test
    void deletingTheDraftGraphDoesNotChangeThePublishedWarningOrTheRunsGraph() throws Exception {
        Fixture f = new Fixture(true);
        var revisionBefore = jdbc.queryForList("select * from workflow_revision where workflow_id=?", f.workflow);
        data(DEMO_OWNER, delete("/processes/" + f.first), null);
        data(DEMO_OWNER, delete("/processes/" + f.second), null);
        assertTrue(stepWarnings(f.readiness(DEMO_OWNER)).isEmpty());
        assertEquals(revisionBefore, jdbc.queryForList("select * from workflow_revision where workflow_id=?", f.workflow));
        String run = f.start();
        assertEquals(1, data(DEMO_OWNER, get("/production-runs/" + run + "/steps"), null).size());
    }

    @Test
    void anUnpublishedWorkflowRetainsItsExistingWorkflowWarning() throws Exception {
        Fixture f = new Fixture(false);
        JsonNode readiness = f.readiness(DEMO_OWNER);
        assertTrue(stepWarnings(readiness).isEmpty());
        List<JsonNode> workflowChecks = new ArrayList<>();
        readiness.path("checks").forEach(check -> {
            if ("workflow".equals(check.path("code").asText())) workflowChecks.add(check);
        });
        assertEquals(1, workflowChecks.size());
        assertEquals("warn", workflowChecks.get(0).path("status").asText());
        assertTrue(workflowChecks.get(0).path("message").asText().contains("no published revision"));
    }

    @ParameterizedTest
    @CsvSource({"viewer,200", "editor,200", "outsider,403", "anonymous,401", "removed,403", "deleted-order,404"})
    void graphWarningsStayBehindCurrentWorkOrderAccess(String role, int expected) throws Exception {
        Fixture f = new Fixture(true);
        f.corruptHistoricalGraph(f.revision, "empty");
        String user = "graph-" + UUID.randomUUID();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", user, user + "@test.local", DEMO_OWNER);
        if (!"outsider".equals(role) && !"anonymous".equals(role)) {
            jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), f.project, user, "viewer".equals(role) ? "viewer" : "editor");
        }
        String token = jwt.generateAccessToken(user);
        if ("removed".equals(role)) {
            mvc.perform(get(f.path()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            jdbc.update("delete from project_member where project_id=? and user_id=?", f.project, user);
        }
        if ("deleted-order".equals(role)) jdbc.update("update work_order set deleted_yn='Y' where work_order_id=?", f.order);
        var before = f.stored();
        var request = get(f.path());
        if (!"anonymous".equals(role)) request.header("Authorization", "Bearer " + token);
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        if (expected == 200) assertEquals("warn", onlyStepWarning(mapper.readTree(response).path("data")).path("status").asText());
        else assertFalse(response.contains("Published revision has no executable graph."));
        assertEquals(before, f.stored());
    }

    private final class Fixture {
        final String project;
        final String workflow;
        final String first;
        final String second;
        final String revision;
        final String order;

        Fixture(boolean published) throws Exception {
            project = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Graph readiness " + UUID.randomUUID(),
                "ownerId", DEMO_OWNER)).path("projectId").asText();
            workflow = data(DEMO_OWNER, post("/workflows"), Map.of("projectId", project, "workflowName", "Readiness graph"))
                .path("workflowId").asText();
            first = node("First");
            second = node("Second");
            data(DEMO_OWNER, post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", first, "toProcessId", second));
            revision = published ? publish() : null;
            order = data(DEMO_OWNER, post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
                "workOrderTitle", "Graph readiness", "targetQuantity", 1)).path("workOrderId").asText();
            data(DEMO_OWNER, post("/work-orders/" + order + "/approve"), null);
        }

        String node(String name) throws Exception {
            return data(DEMO_OWNER, post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String publish() throws Exception {
            return data(DEMO_OWNER, post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        String path() { return "/work-orders/" + order + "/readiness"; }
        JsonNode readiness(String user) throws Exception { return data(user, get(path()), null); }

        String start() throws Exception {
            return data(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", project, "workflowId", workflow,
                "workOrderId", order, "plannedOutputQty", 1, "runType", "simulation")).path("productionRunId").asText();
        }

        List<List<Map<String, Object>>> stored() {
            return List.of(jdbc.queryForList("select * from workflow_revision where workflow_id=? order by revision_no", workflow),
                jdbc.queryForList("select * from work_order where work_order_id=?", order),
                jdbc.queryForList("select * from production_run where project_id=? order by production_run_id", project),
                jdbc.queryForList("select * from flow_run where project_id=? order by flow_run_id", project));
        }

        void corruptHistoricalGraph(String id, String defect) throws Exception {
            // Synthetic old revisions exercise the same compatibility fallback used by legacy unrunnable graphs.
            ObjectNode snapshot = (ObjectNode) mapper.readTree(jdbc.queryForObject(
                "select snapshot_json::text from workflow_revision where workflow_revision_id=?", String.class, id));
            ObjectNode connection = (ObjectNode) snapshot.path("connections").get(0);
            switch (defect) {
                case "empty" -> ((ArrayNode) snapshot.path("processes")).removeAll();
                case "cycle" -> {
                    ObjectNode reverse = connection.deepCopy();
                    reverse.put("connectionId", UUID.randomUUID().toString()).put("fromProcessId", second).put("toProcessId", first);
                    ((ArrayNode) snapshot.path("connections")).add(reverse);
                }
                case "duplicate" -> ((ArrayNode) snapshot.path("processes")).add(snapshot.path("processes").get(0).deepCopy());
                case "missing-process" -> connection.put("toProcessId", "missing");
                case "capacity" -> connection.put("capacity", -1);
                case "condition" -> connection.put("conditionExpr", "quantity >");
                case "policy" -> snapshot.putObject("nodePolicies");
                default -> throw new IllegalArgumentException(defect);
            }
            jdbc.update("update workflow_revision set snapshot_json=cast(? as jsonb) where workflow_revision_id=?", snapshot.toString(), id);
        }
    }

    private static List<JsonNode> stepWarnings(JsonNode readiness) {
        List<JsonNode> warnings = new ArrayList<>();
        readiness.path("checks").forEach(check -> { if ("steps".equals(check.path("code").asText())) warnings.add(check); });
        return warnings;
    }

    private static JsonNode onlyStepWarning(JsonNode readiness) {
        List<JsonNode> warnings = stepWarnings(readiness);
        assertEquals(1, warnings.size(), readiness.toString());
        return warnings.get(0);
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private JsonNode data(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(actor, request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
