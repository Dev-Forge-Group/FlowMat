package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Who worked on a run and for how long, against the crew plan (docs/domain/worker-calendar.md LB1-LB6), on real Postgres. */
@AutoConfigureMockMvc
class RunLaborIntegrationTest extends IntegrationTestSupport {

    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void workedTimeIsRecordedPerPersonSuggestedFromTheirShiftsAndCancelledNotDeleted() throws Exception {
        // kim works around the clock at half share; lee has no shifts at full share.
        String kim = member("editor");
        String lee = member("editor");
        shift(kim, "00:00", "00:00");
        String order = order(null, null);
        crew(order, Map.of("userId", kim, "sharePercent", 50), Map.of("userId", lee, "sharePercent", 100));
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = start(order);
        String path = "/production-runs/" + run + "/labor";

        // Two hours into the run: kim's half of their two working hours, lee's whole run time (LB4).
        jdbc.update("update production_run set created_at = created_at - interval '2 hours' where production_run_id = ?", run);
        call(get(path))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workedMinutes").value(0))
            .andExpect(jsonPath("$.data.people[0].userId").value(kim))
            .andExpect(jsonPath("$.data.people[0].sharePercent").value(50))
            .andExpect(jsonPath("$.data.people[0].suggestedMinutes").value(60))
            .andExpect(jsonPath("$.data.people[0].suggestedFrom").value("shifts"))
            .andExpect(jsonPath("$.data.people[1].userId").value(lee))
            .andExpect(jsonPath("$.data.people[1].suggestedMinutes").value(120))
            .andExpect(jsonPath("$.data.people[1].suggestedFrom").value("run"));

        // 45 minutes for kim; the suggestion shrinks by what counts for them (LB2).
        Map<String, Object> first = labor(kim, 45, "Mixing");
        call(post(path), json(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].userId").value(kim))
            .andExpect(jsonPath("$.data.lines[0].recordedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.people[0].workedMinutes").value(45))
            .andExpect(jsonPath("$.data.people[0].suggestedMinutes").value(15))
            .andExpect(jsonPath("$.data.workedMinutes").value(45));

        // The same record again is answered with the run as it is; different content under its key is refused.
        call(post(path), json(first)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines.length()").value(1));
        Map<String, Object> changed = new HashMap<>(first);
        changed.put("workedMinutes", 40);
        call(post(path), json(changed)).andExpect(status().isConflict());

        // Without a userId the caller worked; someone outside the crew is listed after it, with no suggestion.
        String own = data(call(post(path), json(labor(null, 30, null)))).path("lines").get(1).path("runLaborId").asText();
        call(get(path))
            .andExpect(jsonPath("$.data.people.length()").value(3))
            .andExpect(jsonPath("$.data.people[2].userId").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.people[2].sharePercent").doesNotExist())
            .andExpect(jsonPath("$.data.people[2].suggestedMinutes").doesNotExist())
            .andExpect(jsonPath("$.data.workedMinutes").value(75));

        // Input is checked before anything is saved.
        call(post(path), json(labor(kim, 0, null))).andExpect(status().isBadRequest());
        call(post(path), json(labor(kim, 10_081, null))).andExpect(status().isBadRequest());
        call(post(path), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"workedMinutes\":2.5}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("requestId must be a UUID and workedMinutes a whole number."));
        call(post(path), "{\"requestId\":\"not-a-uuid\",\"workedMinutes\":5}").andExpect(status().isBadRequest());
        call(post(path), json(labor("someone-else", 5, null))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("someone-else is not in this project."));
        call(get(path)).andExpect(jsonPath("$.data.lines.length()").value(2));

        // A viewer reads but does not record; an outsider reads nothing.
        String viewer = member("viewer");
        callAs(viewer, get(path)).andExpect(status().isOk());
        callAs(viewer, post(path).contentType(MediaType.APPLICATION_JSON).content(json(labor(viewer, 5, null))))
            .andExpect(status().isForbidden());
        callAs("unrelated-user", get(path)).andExpect(status().isForbidden());

        // A cancel needs a reason and leaves the row for the record (LB3); kim's suggestion comes back.
        String kims = data(call(get(path))).path("lines").get(0).path("runLaborId").asText();
        call(post(path + "/" + kims + "/cancel"), "{}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Give a reason when cancelling worked time."));
        call(post(path + "/" + kims + "/cancel"), json(Map.of("reason", "Wrong run")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].cancelled").value(true))
            .andExpect(jsonPath("$.data.lines[0].cancelReason").value("Wrong run"))
            .andExpect(jsonPath("$.data.people[0].workedMinutes").value(0))
            .andExpect(jsonPath("$.data.people[0].suggestedMinutes").value(60))
            .andExpect(jsonPath("$.data.workedMinutes").value(30));

        // Material cost does not include it (LB5); a finished run takes no more, and its records stay.
        call(get("/production-runs/" + run + "/cost")).andExpect(status().isOk()).andExpect(jsonPath("$.data.materialCost").value(0));
        call(post("/production-runs/" + run + "/finish"), json(Map.of("actualOutputQty", 1))).andExpect(status().isOk());
        call(post(path), json(labor(kim, 5, null))).andExpect(status().isBadRequest());
        call(post(path + "/" + own + "/cancel"), json(Map.of("reason", "Late"))).andExpect(status().isBadRequest());
        call(get(path)).andExpect(jsonPath("$.data.lines.length()").value(2)).andExpect(jsonPath("$.data.workedMinutes").value(30));
    }

    @Test
    void anOrdersPlannedCrewTimeStandsBesideWhatItsRunsRecorded() throws Exception {
        // 2034-08-14 is a Monday: two days of kim's 09:00-17:00 shifts are 16 hours, half of them 480 minutes (LB6, WC36).
        String kim = member("editor");
        String lee = member("editor");
        shift(kim, "09:00", "17:00");
        String order = order("2034-08-14T09:00:00+09:00", "2034-08-15T17:00:00+09:00");
        crew(order, Map.of("userId", kim, "sharePercent", 50), Map.of("userId", lee, "sharePercent", 100));
        String compare = "/work-orders/" + order + "/labor-comparison";
        call(get(compare))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedMinutes").value(480))
            .andExpect(jsonPath("$.data.plannedComplete").value(false))
            .andExpect(jsonPath("$.data.runs").value(0))
            .andExpect(jsonPath("$.data.actualMinutes").value(0))
            .andExpect(jsonPath("$.data.people[0].plannedMinutes").value(480))
            .andExpect(jsonPath("$.data.people[1].userId").value(lee))
            .andExpect(jsonPath("$.data.people[1].plannedMinutes").doesNotExist());

        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = start(order);
        String path = "/production-runs/" + run + "/labor";
        call(post(path), json(labor(kim, 300, null))).andExpect(status().isOk());
        call(post(path), json(labor(null, 20, null))).andExpect(status().isOk());
        String mistake = data(call(post(path), json(labor(lee, 50, null)))).path("lines").get(2).path("runLaborId").asText();
        call(post(path + "/" + mistake + "/cancel"), json(Map.of("reason", "Not here"))).andExpect(status().isOk());
        call(get(compare))
            .andExpect(jsonPath("$.data.runs").value(1))
            .andExpect(jsonPath("$.data.actualMinutes").value(320))
            .andExpect(jsonPath("$.data.people[0].actualMinutes").value(300))
            .andExpect(jsonPath("$.data.people[1].actualMinutes").value(0))
            .andExpect(jsonPath("$.data.people[2].userId").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.people[2].sharePercent").doesNotExist())
            .andExpect(jsonPath("$.data.people[2].actualMinutes").value(20));

        // Without a crew there is no plan to compare with; outsiders cannot read it.
        String bare = order(null, null);
        call(get("/work-orders/" + bare + "/labor-comparison"))
            .andExpect(jsonPath("$.data.plannedMinutes").doesNotExist())
            .andExpect(jsonPath("$.data.plannedComplete").value(true))
            .andExpect(jsonPath("$.data.people.length()").value(0));
        callAs("unrelated-user", get(compare)).andExpect(status().isForbidden());
    }

    @Test
    void workedTimeCanNameAProcessStepOfTheRun() throws Exception {
        // Mix → Bake, published; the run starts with Mix planned (ADR-011).
        String workflow = data(call(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Labor steps " + UUID.randomUUID())))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        call(post("/process-connections"), json(Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake)))
            .andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + workflow + "/revisions"))).path("workflowRevisionId").asText();
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "plannedOutputQty", 1, "runType", "simulation")))).path("productionRunId").asText();
        String mixStep = null;
        for (JsonNode step : data(call(get("/production-runs/" + run + "/steps")))) {
            if ("Mix".equals(step.path("nodeName").asText())) mixStep = step.path("stepId").asText();
        }
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/start")).andExpect(status().isOk());
        String path = "/production-runs/" + run + "/labor";

        // 30 minutes on Mix and 10 on the whole run (LB13-LB15).
        Map<String, Object> onMix = labor(null, 30, null);
        onMix.put("stepId", mixStep);
        call(post(path), json(onMix)).andExpect(status().isOk());
        call(post(path), json(labor(null, 10, null))).andExpect(status().isOk());
        call(get(path))
            .andExpect(jsonPath("$.data.lines[0].stepId").value(mixStep))
            .andExpect(jsonPath("$.data.lines[0].stepName").value("Mix"))
            .andExpect(jsonPath("$.data.lines[1].stepId").doesNotExist())
            .andExpect(jsonPath("$.data.steps.length()").value(1))
            .andExpect(jsonPath("$.data.steps[0].nodeName").value("Mix"))
            .andExpect(jsonPath("$.data.steps[0].status").value("running"))
            .andExpect(jsonPath("$.data.steps[0].startedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.steps[0].workedMinutes").value(30))
            .andExpect(jsonPath("$.data.workedMinutes").value(40));

        // The same key with another step is a different record; a step not of this run is refused.
        Map<String, Object> moved = new HashMap<>(onMix);
        moved.remove("stepId");
        call(post(path), json(moved)).andExpect(status().isConflict());
        Map<String, Object> elsewhere = labor(null, 5, null);
        elsewhere.put("stepId", "missing");
        call(post(path), json(elsewhere)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("stepId is not a process step of this run."));
        call(get(path)).andExpect(jsonPath("$.data.lines.length()").value(2));
    }

    @Test
    void anOrdersWorkedTimeAddsUpByProcess() throws Exception {
        // A physical run of a work order on Mix → Bake: 30 minutes on Mix, 10 on the whole run (LB18).
        String workflow = data(call(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Labor by process " + UUID.randomUUID())))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        call(post("/process-connections"), json(Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake)))
            .andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + workflow + "/revisions"))).path("workflowRevisionId").asText();
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workOrderTitle", "Labor by process " + UUID.randomUUID(), "targetQuantity", 1)))).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 1)))).path("productionRunId").asText();
        String mixStep = null;
        for (JsonNode step : data(call(get("/production-runs/" + run + "/steps")))) {
            if ("Mix".equals(step.path("nodeName").asText())) mixStep = step.path("stepId").asText();
        }
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/start")).andExpect(status().isOk());
        Map<String, Object> onMix = labor(null, 30, null);
        onMix.put("stepId", mixStep);
        call(post("/production-runs/" + run + "/labor"), json(onMix)).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/labor"), json(labor(null, 10, null))).andExpect(status().isOk());

        call(get("/work-orders/" + order + "/labor-comparison"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actualMinutes").value(40))
            .andExpect(jsonPath("$.data.steps.length()").value(1))
            .andExpect(jsonPath("$.data.steps[0].nodeId").value(mix))
            .andExpect(jsonPath("$.data.steps[0].nodeName").value("Mix"))
            .andExpect(jsonPath("$.data.steps[0].actualMinutes").value(30))
            .andExpect(jsonPath("$.data.wholeRunMinutes").value(10));
    }

    private String process(String workflowId, String name) throws Exception {
        return data(call(post("/processes"), json(Map.of("workflowId", workflowId, "processName", name, "nodeType", "process"))))
            .path("processId").asText();
    }

    /** A new member of the demo project, with no shifts yet. */
    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }

    /** One shift every day, from the start: a saved version applies from today, and the run may begin yesterday near midnight. */
    private void shift(String userId, String start, String end) throws Exception {
        call(put("/projects/" + DEMO_PROJECT + "/workers/" + userId + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", start, "shiftEnd", end, "workDays", EVERY_DAY))))).andExpect(status().isOk());
        jdbc.update("update worker_schedule set effective_from = null where project_id = ? and user_id = ?", DEMO_PROJECT, userId);
    }

    @SafeVarargs
    private void crew(String workOrderId, Map<String, Object>... members) throws Exception {
        call(put("/work-orders/" + workOrderId + "/crew"), json(Map.of("members", List.of(members)))).andExpect(status().isOk());
    }

    private String order(String plannedStartAt, String plannedEndAt) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Labor " + UUID.randomUUID(), "targetQuantity", 1));
        if (plannedStartAt != null) {
            body.put("plannedStartAt", plannedStartAt);
            body.put("plannedEndAt", plannedEndAt);
        }
        return data(call(post("/work-orders"), json(body))).path("workOrderId").asText();
    }

    private String start(String workOrderId) throws Exception {
        return data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", workOrderId, "plannedOutputQty", 1)))).path("productionRunId").asText();
    }

    private static Map<String, Object> labor(String userId, int minutes, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("workedMinutes", minutes);
        if (userId != null) {
            body.put("userId", userId);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)));
    }
}
