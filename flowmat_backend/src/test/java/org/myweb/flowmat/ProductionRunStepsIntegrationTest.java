package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.application.FlowRunTimeoutWatcher;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A production run's process steps and process-level instruction steps (docs/architecture/adr/ADR-011-production-steps.md). */
@AutoConfigureMockMvc
class ProductionRunStepsIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private FlowRunTimeoutWatcher watcher;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void stepsFollowTheWorkflowTakeTheRecordedOutputAndWaitForTheProcessInstruction() throws Exception {
        Line line = new Line();
        String bread = item("BREAD");
        String instruction = data(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT, "itemId", bread, "title", "Bread",
            "blocksFinish", true)).path("instructionId").asText();
        refused(post("/work-instructions/" + instruction + "/steps"), Map.of("text", "Somewhere", "processId", "nope"), 400,
            "processId is not a process of this project.");
        JsonNode instructionStep = data(post("/work-instructions/" + instruction + "/steps"),
            Map.of("text", "Check the oven", "required", true, "processId", line.bake)).path("steps").get(0);
        assertEquals(line.bake, instructionStep.path("processId").asText());
        assertEquals("Bake", instructionStep.path("processName").asText());
        data(post("/work-instructions/" + instruction + "/release"), null);

        String run = line.start(bread);
        JsonNode first = only(steps(run));
        assertEquals("Mix", first.path("nodeName").asText());
        assertEquals("planned", first.path("status").asText());
        assertFalse(first.path("gateway").asBoolean());
        assertEquals(0, first.path("instructionRequired").asInt());
        // The flow run's own step API still refuses a production-linked run.
        String flowRun = flowRunOf(line.workflow, run);
        refused(post("/flow-runs/" + flowRun + "/steps/" + first.path("stepId").asText() + "/start"), null, 409,
            "Production-linked steps must be recorded through the production run API.");

        String mix = first.path("stepId").asText();
        assertEquals("running", data(post(step(run, mix, "start")), null).path("status").asText());
        data(post("/production-runs/" + run + "/items"), Map.of("processId", line.mix, "itemId", bread, "direction", "output",
            "plannedQty", 100, "actualQty", 98, "unit", "kg"));
        JsonNode mixed = data(post(step(run, mix, "complete")), Map.of());
        assertEquals("completed", mixed.path("status").asText());
        assertQuantity("98", mixed.path("outputSnapshot"));
        assertEquals("kg", mixed.path("outputSnapshot").path("unit").asText());
        assertEquals(bread, mixed.path("outputSnapshot").path("item").asText());

        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals("planned", bake.path("status").asText());
        assertQuantity("98", bake.path("inputSnapshot"));
        assertEquals(1, bake.path("instructionRequired").asInt());
        assertEquals(0, bake.path("instructionDone").asInt());
        String baking = bake.path("stepId").asText();
        data(post(step(run, baking, "start")), null);
        refused(post(step(run, baking, "complete")), Map.of(), 409, "Confirm the required work instruction step 1 for Bake first.");
        data(post("/production-runs/" + run + "/instruction/steps/" + instructionStep.path("stepId").asText() + "/check"), Map.of());
        JsonNode baked = data(post(step(run, baking, "complete")), Map.of("outputSnapshot", Map.of("quantity", 97, "unit", "kg")));
        assertEquals("completed", baked.path("status").asText());
        assertEquals(1, baked.path("instructionDone").asInt());
        assertQuantity("97", baked.path("outputSnapshot"));

        data(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 97));
        assertEquals("finished", data(get("/flow-runs/" + flowRun), null).path("status").asText());

        // A new revision of the instruction keeps the step's process.
        JsonNode revised = data(post("/work-instructions/" + instruction + "/revise"), null);
        assertEquals(line.bake, revised.path("steps").get(0).path("processId").asText());
    }

    @Test
    void finishingRefusesARunningStepCancelsTheRestAndAFailedStepDoesNotHoldItUp() throws Exception {
        Line line = new Line();
        String roll = item("ROLL");
        String run = line.start(roll);
        String mix = only(steps(run)).path("stepId").asText();
        data(post(step(run, mix, "start")), null);
        refused(post("/production-runs/" + run + "/finish"), Map.of(), 409,
            "Step #1 Mix is still running; complete or fail it before finishing the run.");
        JsonNode mixed = data(post(step(run, mix, "complete")), null);
        assertTrue(mixed.path("outputSnapshot").isEmpty(), "nothing recorded: " + mixed.path("outputSnapshot"));
        data(post("/production-runs/" + run + "/finish"), Map.of());
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals("cancelled", bake.path("status").asText());
        assertEquals("RUN_FINISHED", bake.path("errorCode").asText());
        String flowRun = flowRunOf(line.workflow, run);
        assertEquals("finished", data(get("/flow-runs/" + flowRun), null).path("status").asText());
        assertTrue(events(flowRun).stream().anyMatch(event -> "step_cancelled".equals(event.path("eventType").asText())
            && "run_finished".equals(event.path("payload").path("reason").asText())));
        call(post(step(run, bake.path("stepId").asText(), "start")), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(Matchers.containsString("is finished")));

        String second = line.start(roll);
        String again = only(steps(second)).path("stepId").asText();
        data(post(step(second, again, "start")), null);
        JsonNode failed = data(post(step(second, again, "fail")), Map.of("errorCode", "JAM", "errorMessage", "The mixer jammed."));
        assertEquals("failed", failed.path("status").asText());
        String secondFlow = flowRunOf(line.workflow, second);
        assertEquals("failed", data(get("/flow-runs/" + secondFlow), null).path("status").asText());
        refused(post(step(second, again, "start")), null, 409, "A failed step stopped this run's process steps.");
        data(post("/production-runs/" + second + "/finish"), Map.of());
        assertEquals("failed", data(get("/flow-runs/" + secondFlow), null).path("status").asText());
    }

    @Test
    void aStepPastItsTimeLimitIsOnlyMarkedLateAndCanStillComplete() throws Exception {
        Line line = new Line(Map.of("expectedVersion", 0, "timeoutSeconds", 60));
        String run = line.start(item("LATE"));
        String mix = only(steps(run)).path("stepId").asText();
        JsonNode started = data(post(step(run, mix, "start")), null);
        assertNotNull(started.path("timeoutAt").asText(null), started.toString());
        assertNull(started.path("overdueAt").asText(null), started.toString());

        jdbc.update("update flow_run_step_attempt set started_at = now() - interval '5 minutes', timeout_at = now() - interval '1 minute'"
            + " where step_id = ? and status = 'running'", mix);
        watcher.sweep();
        watcher.sweep();
        JsonNode late = only(steps(run));
        assertEquals("running", late.path("status").asText());
        assertNotNull(late.path("overdueAt").asText(null), late.toString());
        String flowRun = flowRunOf(line.workflow, run);
        assertEquals("running", data(get("/flow-runs/" + flowRun), null).path("status").asText());
        List<String> types = events(flowRun).stream().map(event -> event.path("eventType").asText()).toList();
        assertEquals(1, types.stream().filter("step_overdue"::equals).count(), types.toString());
        assertFalse(types.contains("step_timed_out"), types.toString());

        // Late is only a mark: the step still completes and the run goes on.
        assertEquals("completed", data(post(step(run, mix, "complete")), Map.of()).path("status").asText());
        assertEquals("planned", forNode(steps(run), line.bake).path("status").asText());
    }

    @Test
    void aRevisionThatCannotRunAsAGraphKeepsNoSteps() throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Empty " + UUID.randomUUID()))
            .path("workflowId").asText();
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        String run = data(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "plannedOutputQty", 2, "runType", "simulation")).path("productionRunId").asText();
        assertEquals(0, steps(run).size());
        String flowRun = flowRunOf(workflow, run);
        assertTrue(events(flowRun).stream().anyMatch(event -> "steps_unavailable".equals(event.path("eventType").asText())));
        refused(post(step(run, "nothing", "start")), null, 409,
            "This run has no process steps: it started before they were added, or its workflow revision cannot run as a graph.");
        data(post("/production-runs/" + run + "/finish"), Map.of());
    }

    @Test
    void readinessWarnsWhenRunsWouldStartWithoutProcessSteps() throws Exception {
        // An empty workflow's published revision cannot run as a graph, so its runs keep no steps (above).
        String empty = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Empty " + UUID.randomUUID()))
            .path("workflowId").asText();
        data(post("/workflows/" + empty + "/revisions"), null);
        String bare = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", empty,
            "workOrderTitle", "No steps " + UUID.randomUUID(), "targetQuantity", 1)).path("workOrderId").asText();
        JsonNode warned = null;
        for (JsonNode check : data(get("/work-orders/" + bare + "/readiness"), null).path("checks")) {
            if ("steps".equals(check.path("code").asText())) warned = check;
        }
        assertNotNull(warned);
        assertEquals("warn", warned.path("status").asText());
        assertEquals("Runs will start without process steps: Published revision has no executable graph.",
            warned.path("message").asText());

        // A workflow that runs as a graph says nothing about steps.
        Line line = new Line();
        String order = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", line.workflow,
            "workOrderTitle", "Steps " + UUID.randomUUID(), "targetQuantity", 1)).path("workOrderId").asText();
        List<String> codes = new ArrayList<>();
        data(get("/work-orders/" + order + "/readiness"), null).path("checks").forEach(check -> codes.add(check.path("code").asText()));
        assertTrue(codes.contains("workflow"), codes.toString());
        assertFalse(codes.contains("steps"), codes.toString());
    }

    /** A published workflow Mix -> Bake in the demo project. */
    private final class Line {
        final String workflow;
        final String mix;
        final String bake;
        final String revision;

        Line() throws Exception {
            this(null);
        }

        /** {@code mixPolicy}: Mix's execution policy, set before the revision is taken; null for none. */
        Line(Map<String, Object> mixPolicy) throws Exception {
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Line " + UUID.randomUUID()))
                .path("workflowId").asText();
            mix = node("Mix");
            bake = node("Bake");
            data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake));
            if (mixPolicy != null) {
                data(put("/processes/" + mix + "/execution-policy"), mixPolicy);
            }
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        private String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String start(String itemId) throws Exception {
            return data(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
                "workflowRevisionId", revision, "targetItemId", itemId, "plannedOutputQty", 100, "runType", "simulation"))
                .path("productionRunId").asText();
        }
    }

    private String item(String prefix) throws Exception {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return data(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "product", "unitId", "unit_kg")).path("itemId").asText();
    }

    private static String step(String run, String stepId, String action) {
        return "/production-runs/" + run + "/steps/" + stepId + "/" + action;
    }

    private JsonNode steps(String run) throws Exception {
        return data(get("/production-runs/" + run + "/steps"), null);
    }

    private static JsonNode only(JsonNode steps) {
        assertEquals(1, steps.size(), steps.toString());
        return steps.get(0);
    }

    private static JsonNode forNode(JsonNode steps, String nodeId) {
        List<JsonNode> found = new ArrayList<>();
        steps.forEach(one -> {
            if (nodeId.equals(one.path("nodeId").asText())) found.add(one);
        });
        assertEquals(1, found.size(), steps.toString());
        return found.get(0);
    }

    private String flowRunOf(String workflow, String productionRun) throws Exception {
        for (JsonNode one : data(get("/flow-runs").param("workflowId", workflow), null)) {
            if (productionRun.equals(one.path("productionRunId").asText())) {
                return one.path("flowRunId").asText();
            }
        }
        throw new AssertionError("No flow run for " + productionRun);
    }

    private List<JsonNode> events(String flowRun) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        data(get("/flow-runs/" + flowRun + "/events"), null).forEach(found::add);
        return found;
    }

    private static void assertQuantity(String expected, JsonNode snapshot) {
        assertTrue(snapshot.path("quantity").isNumber(), "quantity " + snapshot);
        assertEquals(0, new BigDecimal(expected).compareTo(snapshot.path("quantity").decimalValue()), "quantity " + snapshot);
    }

    private void refused(MockHttpServletRequestBuilder request, Object body, int code, String message) throws Exception {
        call(request, body).andExpect(status().is(code)).andExpect(jsonPath("$.message").value(message));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
