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

/** A step plan's process split among people beside what each recorded on it (docs/domain/worker-calendar.md LB29), on real Postgres. */
@AutoConfigureMockMvc
class WorkOrderStepPlanPeopleIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aProcessSplitAmongPeopleStandsBesideEachPersonsMinutes() throws Exception {
        String kim = member();
        String lee = member();
        String dan = member();
        // Mix → Bake.
        String workflow = data(call(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Split plan " + UUID.randomUUID())))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        call(post("/process-connections"), json(Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake)))
            .andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + workflow + "/revisions"))).path("workflowRevisionId").asText();
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workOrderTitle", "Split plan " + UUID.randomUUID(), "targetQuantity", 1)))).path("workOrderId").asText();
        String plan = "/work-orders/" + order + "/step-plan";

        // People of the project, each once, whole minutes, adding up to the step's minutes when those are given too.
        for (Object wrong : List.of(split(mix, null, person("outsider", 60)), split(mix, null, person(kim, 60), person(kim, 30)),
                split(mix, null, person(kim, 0)), split(mix, 100, person(kim, 60), person(lee, 30)),
                split(mix, null, person(kim, 60_000), person(lee, 50_000)))) {
            call(put(plan), json(Map.of("steps", List.of(wrong)))).andExpect(status().isBadRequest());
        }

        // Mix split 120 for kim and 60 for lee, its minutes left out; Bake 180 for everyone together.
        call(put(plan), json(Map.of("steps", List.of(split(mix, null, person(kim, 120), person(lee, 60)), Map.of("nodeId", bake,
                "plannedMinutes", 180)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stepPlannedMinutes").value(360))
            .andExpect(jsonPath("$.data.steps[0].plannedMinutes").value(180))
            .andExpect(jsonPath("$.data.steps[0].people.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].people[0].userId").value(kim))
            .andExpect(jsonPath("$.data.steps[0].people[0].plannedMinutes").value(120))
            .andExpect(jsonPath("$.data.steps[0].people[0].actualMinutes").value(0))
            .andExpect(jsonPath("$.data.steps[1].people.length()").value(0));
        // The step's minutes may be given when they agree.
        call(put(plan), json(Map.of("steps", List.of(split(mix, 180, person(kim, 120), person(lee, 60)), Map.of("nodeId", bake,
            "plannedMinutes", 180))))).andExpect(status().isOk());

        // kim records 100 minutes on Mix and dan, who is not in the plan, 20.
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 1)))).path("productionRunId").asText();
        String mixStep = null;
        for (JsonNode one : data(call(get("/production-runs/" + run + "/steps")))) {
            if ("Mix".equals(one.path("nodeName").asText())) mixStep = one.path("stepId").asText();
        }
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/start")).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/labor"), json(labor(kim, 100, mixStep))).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/labor"), json(labor(dan, 20, mixStep))).andExpect(status().isOk());
        call(get("/work-orders/" + order + "/labor-comparison"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].actualMinutes").value(120))
            .andExpect(jsonPath("$.data.steps[0].people.length()").value(3))
            .andExpect(jsonPath("$.data.steps[0].people[0].userId").value(kim))
            .andExpect(jsonPath("$.data.steps[0].people[0].actualMinutes").value(100))
            .andExpect(jsonPath("$.data.steps[0].people[1].userId").value(lee))
            .andExpect(jsonPath("$.data.steps[0].people[1].plannedMinutes").value(60))
            .andExpect(jsonPath("$.data.steps[0].people[1].actualMinutes").value(0))
            .andExpect(jsonPath("$.data.steps[0].people[2].userId").value(dan))
            .andExpect(jsonPath("$.data.steps[0].people[2].plannedMinutes").isEmpty())
            .andExpect(jsonPath("$.data.steps[0].people[2].actualMinutes").value(20));

        // A plan for everyone together again leaves only who recorded time.
        call(put(plan), json(Map.of("steps", List.of(Map.of("nodeId", mix, "plannedMinutes", 200)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].plannedMinutes").value(200))
            .andExpect(jsonPath("$.data.steps[0].people.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].people[0].plannedMinutes").isEmpty());
    }

    @SafeVarargs
    private static Map<String, Object> split(String nodeId, Integer minutes, Map<String, Object>... people) {
        Map<String, Object> step = new HashMap<>();
        step.put("nodeId", nodeId);
        if (minutes != null) step.put("plannedMinutes", minutes);
        step.put("people", List.of(people));
        return step;
    }

    private static Map<String, Object> person(String userId, int minutes) {
        return Map.of("userId", userId, "plannedMinutes", minutes);
    }

    private static Map<String, Object> labor(String userId, int minutes, String stepId) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("userId", userId);
        body.put("workedMinutes", minutes);
        body.put("stepId", stepId);
        return body;
    }

    private String process(String workflowId, String name) throws Exception {
        return data(call(post("/processes"), json(Map.of("workflowId", workflowId, "processName", name, "nodeType", "process"))))
            .path("processId").asText();
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

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
