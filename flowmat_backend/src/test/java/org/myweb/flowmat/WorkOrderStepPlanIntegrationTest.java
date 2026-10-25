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

/** A work order's planned worker minutes by process beside what its runs recorded (docs/domain/worker-calendar.md LB23), on real Postgres. */
@AutoConfigureMockMvc
class WorkOrderStepPlanIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theStepPlanStandsBesideTheMinutesEachProcessTook() throws Exception {
        // Mix → Bake, planned at 240 and 180 worker minutes.
        String workflow = data(call(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Step plan " + UUID.randomUUID())))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        call(post("/process-connections"), json(Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake)))
            .andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + workflow + "/revisions"))).path("workflowRevisionId").asText();
        String other = process(data(call(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Other " + UUID.randomUUID())))).path("workflowId").asText(), "Other");
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workOrderTitle", "Step plan " + UUID.randomUUID(), "targetQuantity", 1)))).path("workOrderId").asText();
        String plan = "/work-orders/" + order + "/step-plan";

        // Processes of the order's own workflow, each once, whole minutes from 1 to 100000, by someone who can edit.
        call(put(plan), json(Map.of("steps", List.of(step(other, 60))))).andExpect(status().isBadRequest());
        call(put(plan), json(Map.of("steps", List.of(step(mix, 60), step(mix, 30))))).andExpect(status().isBadRequest());
        call(put(plan), json(Map.of("steps", List.of(step(mix, 0))))).andExpect(status().isBadRequest());
        call(put(plan), json(Map.of("steps", List.of(step(mix, 100_001))))).andExpect(status().isBadRequest());
        call(put(plan), "{\"steps\":[{\"nodeId\":\"" + mix + "\",\"plannedMinutes\":2.5}]}").andExpect(status().isBadRequest());
        callAs(member("viewer"), put(plan).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("steps", List.of(step(mix, 60))))))
            .andExpect(status().isForbidden());
        call(put("/work-orders/missing/step-plan"), json(Map.of("steps", List.of()))).andExpect(status().isNotFound());

        call(put(plan), json(Map.of("steps", List.of(step(mix, 240), step(bake, 180)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stepPlannedMinutes").value(420))
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].nodeName").value("Mix"))
            .andExpect(jsonPath("$.data.steps[0].plannedMinutes").value(240))
            .andExpect(jsonPath("$.data.steps[0].actualMinutes").value(0))
            .andExpect(jsonPath("$.data.steps[0].sequenceNo").isEmpty())
            .andExpect(jsonPath("$.data.steps[1].nodeName").value("Bake"));

        // 30 minutes on Mix and 10 on the whole run: Mix carries its plan, Bake is planned but has not run.
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 1)))).path("productionRunId").asText();
        String mixStep = null;
        for (JsonNode one : data(call(get("/production-runs/" + run + "/steps")))) {
            if ("Mix".equals(one.path("nodeName").asText())) mixStep = one.path("stepId").asText();
        }
        call(post("/production-runs/" + run + "/steps/" + mixStep + "/start")).andExpect(status().isOk());
        Map<String, Object> onMix = labor(30);
        onMix.put("stepId", mixStep);
        call(post("/production-runs/" + run + "/labor"), json(onMix)).andExpect(status().isOk());
        call(post("/production-runs/" + run + "/labor"), json(labor(10))).andExpect(status().isOk());
        call(get("/work-orders/" + order + "/labor-comparison"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].nodeId").value(mix))
            .andExpect(jsonPath("$.data.steps[0].actualMinutes").value(30))
            .andExpect(jsonPath("$.data.steps[0].plannedMinutes").value(240))
            .andExpect(jsonPath("$.data.steps[0].sequenceNo").isNumber())
            .andExpect(jsonPath("$.data.steps[1].nodeId").value(bake))
            .andExpect(jsonPath("$.data.steps[1].actualMinutes").value(0))
            .andExpect(jsonPath("$.data.steps[1].plannedMinutes").value(180))
            .andExpect(jsonPath("$.data.wholeRunMinutes").value(10))
            .andExpect(jsonPath("$.data.stepPlannedMinutes").value(420));

        // An empty plan clears it; the actual minutes stay.
        call(put(plan), json(Map.of("steps", List.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stepPlannedMinutes").isEmpty())
            .andExpect(jsonPath("$.data.steps.length()").value(1))
            .andExpect(jsonPath("$.data.steps[0].plannedMinutes").isEmpty());

        // An order on no workflow has no steps to plan.
        Map<String, Object> bare = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "No workflow " + UUID.randomUUID(),
            "targetQuantity", 1));
        String loose = data(call(post("/work-orders"), json(bare))).path("workOrderId").asText();
        call(put("/work-orders/" + loose + "/step-plan"), json(Map.of("steps", List.of()))).andExpect(status().isConflict());
    }

    private static Map<String, Object> step(String nodeId, int minutes) {
        return Map.of("nodeId", nodeId, "plannedMinutes", minutes);
    }

    private static Map<String, Object> labor(int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("workedMinutes", minutes);
        return body;
    }

    private String process(String workflowId, String name) throws Exception {
        return data(call(post("/processes"), json(Map.of("workflowId", workflowId, "processName", name, "nodeType", "process"))))
            .path("processId").asText();
    }

    /** A new member of the demo project. */
    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
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
