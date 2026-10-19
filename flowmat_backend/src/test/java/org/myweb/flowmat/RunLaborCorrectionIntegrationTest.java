package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Worked-time corrections of finished runs (docs/domain/worker-calendar.md LB9-LB11) against real Postgres. */
@AutoConfigureMockMvc
class RunLaborCorrectionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aCorrectionCancelsAndAddsWorkedTimeOfAFinishedRun() throws Exception {
        String kim = member();
        String run = run();
        String first = data(call(post(labor(run)), worked(kim, 45))).path("lines").get(0).path("runLaborId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        // Recording on the finished run is refused; a correction is the way to change its worked time.
        call(post(labor(run)), worked(kim, 5)).andExpect(status().isBadRequest());

        Map<String, Object> request = Map.of("reason", "Kim was counted twice", "lines", List.of(
            Map.of("kind", "cancel_labor", "targetRunLaborId", first),
            Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 30),
            Map.of("kind", "add_labor", "userId", DEMO_OWNER, "workedMinutes", 15)));
        String correction = data(call(post(corrections(run)), request)).path("productionRunCorrectionId").asText();
        // Nothing changes until the project owner approves.
        call(get(labor(run)), null).andExpect(jsonPath("$.data.workedMinutes").value(45));
        call(post(corrections(run) + "/" + correction + "/approve"), Map.of())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("applied"))
            .andExpect(jsonPath("$.data.lines[0].kind").value("cancel_labor"))
            .andExpect(jsonPath("$.data.lines[0].targetRunLaborId").value(first))
            .andExpect(jsonPath("$.data.lines[1].kind").value("add_labor"))
            .andExpect(jsonPath("$.data.lines[1].userId").value(kim))
            .andExpect(jsonPath("$.data.lines[1].workedMinutes").value(30))
            .andExpect(jsonPath("$.data.lines[1].createdRunLaborId").isNotEmpty());

        JsonNode after = data(call(get(labor(run)), null));
        JsonNode cancelled = null;
        JsonNode added = null;
        for (JsonNode line : after.path("lines")) {
            if (first.equals(line.path("runLaborId").asText())) cancelled = line;
            else if (kim.equals(line.path("userId").asText())) added = line;
        }
        assertNotNull(cancelled);
        assertNotNull(added);
        assertEquals(true, cancelled.path("cancelled").asBoolean());
        assertEquals("Correction #1: Kim was counted twice", cancelled.path("cancelReason").asText());
        assertEquals(30, added.path("workedMinutes").asInt());
        assertEquals("Correction #1: Kim was counted twice", added.path("note").asText());
        assertEquals(DEMO_OWNER, added.path("recordedBy").asText());
        assertEquals(45, after.path("workedMinutes").asInt());
        assertEquals(3, after.path("lines").size());
        // Still apart from material cost (LB5).
        call(get("/production-runs/" + run + "/cost"), null).andExpect(jsonPath("$.data.materialCost").value(0));
    }

    @Test
    void workedTimeCorrectionLinesAreChecked() throws Exception {
        String kim = member();
        String run = run();
        String laborId = data(call(post(labor(run)), worked(kim, 5))).path("lines").get(0).path("runLaborId").asText();
        String gone = data(call(post(labor(run)), worked(kim, 7))).path("lines").get(1).path("runLaborId").asText();
        call(post(labor(run) + "/" + gone + "/cancel"), Map.of("reason", "typed twice")).andExpect(status().isOk());
        // An open run is not corrected: its worked time is recorded and cancelled on the run.
        call(post(corrections(run)), correction(Map.of("kind", "cancel_labor", "targetRunLaborId", laborId)))
            .andExpect(status().isBadRequest());
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());

        call(post(corrections(run)), correction(Map.of("kind", "cancel_labor", "targetRunLaborId", "missing")))
            .andExpect(status().isNotFound());
        call(post(corrections(run)), correction(Map.of("kind", "cancel_labor", "targetRunLaborId", gone)))
            .andExpect(status().isConflict());
        call(post(corrections(run)), correction(Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("workedMinutes must be whole minutes")));
        call(post(corrections(run)), correction(Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 10_081)))
            .andExpect(status().isBadRequest());
        call(post(corrections(run)), correction(Map.of("kind", "add_labor", "workedMinutes", 5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose who worked in the time to add."));
        call(post(corrections(run)), correction(Map.of("kind", "add_labor", "userId", "someone-else", "workedMinutes", 5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("someone-else is not in this project."));
        callRaw(post(corrections(run)), "{\"reason\":\"x\",\"lines\":[{\"kind\":\"add_labor\",\"userId\":\"" + kim
                + "\",\"workedMinutes\":2.5}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Send the correction as JSON, with quantities as numbers and minutes as whole numbers."));
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(
            Map.of("kind", "cancel_labor", "targetRunLaborId", laborId),
            Map.of("kind", "cancel_labor", "targetRunLaborId", laborId))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The same worked time is cancelled twice."));
        // The refused requests left nothing behind.
        call(get(corrections(run)), null).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aCorrectionCanAddWorkedTimeOnAProcessStep() throws Exception {
        // Mix → Bake, published; Mix done and the run finished, so the planned Bake is cancelled (F11).
        String workflow = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Corrected steps " + UUID.randomUUID()))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        call(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake))
            .andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + workflow + "/revisions"), null)).path("workflowRevisionId").asText();
        String run = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "plannedOutputQty", 1, "runType", "simulation"))).path("productionRunId").asText();
        String mixStep = stepOf(run, "Mix");
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/start"), null).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/complete"), Map.of()).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());

        // A step not of this run is refused as on a live record (LB13, LB17).
        call(post(corrections(run)), correction(Map.of("kind", "add_labor", "userId", DEMO_OWNER, "workedMinutes", 5,
            "stepId", "missing"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("stepId is not a process step of this run."));
        String correction = data(call(post(corrections(run)), correction(Map.of("kind", "add_labor", "userId", DEMO_OWNER,
            "workedMinutes", 25, "stepId", mixStep)))).path("productionRunCorrectionId").asText();
        call(post(corrections(run) + "/" + correction + "/approve"), Map.of())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].stepId").value(mixStep));
        call(get(labor(run)), null)
            .andExpect(jsonPath("$.data.lines[0].stepId").value(mixStep))
            .andExpect(jsonPath("$.data.lines[0].stepName").value("Mix"))
            .andExpect(jsonPath("$.data.steps[0].nodeName").value("Mix"))
            .andExpect(jsonPath("$.data.steps[0].workedMinutes").value(25));
    }

    private String process(String workflowId, String name) throws Exception {
        return data(call(post("/processes"), Map.of("workflowId", workflowId, "processName", name, "nodeType", "process")))
            .path("processId").asText();
    }

    private String stepOf(String run, String nodeName) throws Exception {
        for (JsonNode step : data(call(get("/production-runs/" + run + "/steps"), null))) {
            if (nodeName.equals(step.path("nodeName").asText())) return step.path("stepId").asText();
        }
        throw new AssertionError("No step " + nodeName);
    }

    /** A new editor of the demo project. */
    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private String run() throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private static String labor(String run) {
        return "/production-runs/" + run + "/labor";
    }

    private static String corrections(String run) {
        return "/production-runs/" + run + "/corrections";
    }

    private static Map<String, Object> correction(Map<String, Object> line) {
        return Map.of("reason", "x", "lines", List.of(line));
    }

    private static Map<String, Object> worked(String userId, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("userId", userId);
        body.put("workedMinutes", minutes);
        return body;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }

    private ResultActions callRaw(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
