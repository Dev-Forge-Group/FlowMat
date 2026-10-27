package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@Timeout(60)
class LaborPlanSuggestionSafetyIntegrationTest extends IntegrationTestSupport {
    private static final String MONDAY = "2041-05-06T09:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;
    private String openRun;

    @BeforeEach
    void createPlanningProject() throws Exception {
        projectId = data(call(post("/projects"), Map.of("projectName", "Labor safety " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER))).path("projectId").asText();
        JsonNode zone = data(call(get("/projects/" + projectId + "/time-zone")));
        call(put("/projects/" + projectId + "/time-zone"), Map.of("timeZone", "Asia/Seoul", "expectedVersion", zone.path("version").asLong()))
            .andExpect(status().isOk());
    }

    @AfterEach
    void closePlanningFixtures() throws Exception {
        try {
            if (openRun != null) {
                finishRun();
            }
        } finally {
            if (projectId != null) {
                call(delete("/projects/" + projectId)).andExpect(status().isOk());
            }
        }
    }

    @Test
    void aLaborOnlyBatchMovesTheSecondOrderPastTheSharedCrewWithoutSavingDates() throws Exception {
        String worker = member();
        shifts(worker);
        ProcessFixture flow = workflow();
        String first = order("First", flow, 10);
        String second = order("Second", flow, 10);
        for (String orderId : List.of(first, second)) {
            stepPlan(orderId, flow, 60);
            crew(orderId, worker);
        }
        String firstNumber = data(call(get("/work-orders/" + first))).path("workOrderNumber").asText();

        // Each draft fits at 09:00 alone; only the earlier batch placement holds the worker.
        suggestion(second).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedStartAt").value(MONDAY))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-05-06T10:00:00+09:00"));
        call(post("/work-orders/plan-suggestions"), Map.of("projectId", projectId, "workOrderIds", List.of(first, second), "from", MONDAY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].problem").isEmpty())
            .andExpect(jsonPath("$.data.lines[0].suggestion.equipmentId").isEmpty())
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value(MONDAY))
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedEndAt").value("2041-05-06T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].problem").isEmpty())
            .andExpect(jsonPath("$.data.lines[1].suggestion.equipmentId").isEmpty())
            .andExpect(jsonPath("$.data.lines[1].suggestion.laborMinutes").value(60))
            .andExpect(jsonPath("$.data.lines[1].suggestion.plannedStartAt").value("2041-05-06T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].suggestion.plannedEndAt").value("2041-05-06T11:00:00+09:00"))
            .andExpect(jsonPath("$.data.lines[1].suggestion.crewMovedPast[0].workOrderNumber").value(firstNumber))
            .andExpect(jsonPath("$.data.lines[1].suggestion.crewMovedPast[0].userIds[0]").value(worker));
        for (String orderId : List.of(first, second)) {
            call(get("/work-orders/" + orderId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderStatus").value("draft"))
                .andExpect(jsonPath("$.data.plannedStartAt").isEmpty())
                .andExpect(jsonPath("$.data.plannedEndAt").isEmpty());
        }
    }

    @Test
    void equipmentProductionTimeStillDecidesWhenAllPlannedLaborWasAlreadyRecorded() throws Exception {
        String worker = member();
        shifts(worker);
        ProcessFixture flow = workflow();
        String orderId = order("Production remains", flow, 20);
        stepPlan(orderId, flow, 60);
        crew(orderId, worker);
        String equipmentId = data(call(post("/equipments"), Map.of("projectId", projectId,
            "equipmentCode", "LPS-" + UUID.randomUUID().toString().substring(0, 8), "equipmentName", "Labor safety press",
            "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))).path("equipmentId").asText();
        call(put("/work-orders/" + orderId + "/equipment"), Map.of("equipmentId", equipmentId)).andExpect(status().isOk());
        String revision = data(call(post("/workflows/" + flow.workflowId() + "/revisions"))).path("workflowRevisionId").asText();
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        openRun = data(call(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", flow.workflowId(),
            "workflowRevisionId", revision, "workOrderId", orderId, "plannedOutputQty", 20))).path("productionRunId").asText();
        String stepId = null;
        for (JsonNode step : data(call(get("/production-runs/" + openRun + "/steps")))) {
            if (flow.nodeId().equals(step.path("nodeId").asText())) {
                stepId = step.path("stepId").asText();
            }
        }
        assertNotNull(stepId, "The published process must have a run step for its worked minutes.");
        call(post("/production-runs/" + openRun + "/steps/" + stepId + "/start")).andExpect(status().isOk());
        call(post("/production-runs/" + openRun + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", worker, "workedMinutes", 60, "stepId", stepId)).andExpect(status().isOk());
        call(post("/production-runs/" + openRun + "/steps/" + stepId + "/complete"), Map.of()).andExpect(status().isOk());
        // Finishing with no output keeps all twenty units to produce, without making stock rows.
        finishRun();
        call(get("/work-orders/" + orderId + "/labor-comparison")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stepPlannedMinutes").value(60))
            .andExpect(jsonPath("$.data.steps[0].actualMinutes").value(60));

        suggestion(orderId).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.equipmentId").value(equipmentId))
            .andExpect(jsonPath("$.data.remainingQuantity").value(20))
            .andExpect(jsonPath("$.data.laborMinutes").value(0))
            .andExpect(jsonPath("$.data.laborHours").value(0))
            .andExpect(jsonPath("$.data.productionHours").value(2))
            .andExpect(jsonPath("$.data.neededHours").value(2))
            .andExpect(jsonPath("$.data.plannedStartAt").value(MONDAY))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-05-06T11:00:00+09:00"));
    }

    @Test
    void aLaborOnlyOrderCannotInventWorkingTimeForACrewWithoutShifts() throws Exception {
        String worker = member();
        ProcessFixture flow = workflow();
        String orderId = order("No shifts", flow, 10);
        stepPlan(orderId, flow, 60);
        crew(orderId, worker);

        suggestion(orderId).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("with shifts")))
            .andExpect(jsonPath("$.message").value(containsString("when it runs is unknown")));
        // Setting the same person's shifts supplies the fact that was missing.
        shifts(worker);
        suggestion(orderId).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.equipmentId").isEmpty())
            .andExpect(jsonPath("$.data.plannedStartAt").value(MONDAY))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-05-06T10:00:00+09:00"));
    }

    private record ProcessFixture(String workflowId, String nodeId) {
    }

    private ProcessFixture workflow() throws Exception {
        String workflowId = data(call(post("/workflows"), Map.of("projectId", projectId,
            "workflowName", "Labor safety " + UUID.randomUUID()))).path("workflowId").asText();
        String nodeId = data(call(post("/processes"), Map.of("workflowId", workflowId, "processName", "Assemble",
            "nodeType", "process"))).path("processId").asText();
        return new ProcessFixture(workflowId, nodeId);
    }

    private String order(String title, ProcessFixture flow, int quantity) throws Exception {
        return data(call(post("/work-orders"), Map.of("projectId", projectId, "workflowId", flow.workflowId(),
            "workOrderTitle", title, "targetQuantity", quantity))).path("workOrderId").asText();
    }

    private void stepPlan(String orderId, ProcessFixture flow, int minutes) throws Exception {
        call(put("/work-orders/" + orderId + "/step-plan"), Map.of("steps", List.of(Map.of("nodeId", flow.nodeId(),
            "plannedMinutes", minutes)))).andExpect(status().isOk());
    }

    private void crew(String orderId, String worker) throws Exception {
        call(put("/work-orders/" + orderId + "/crew"), Map.of("members", List.of(Map.of("userId", worker,
            "sharePercent", 100)))).andExpect(status().isOk());
    }

    private void shifts(String worker) throws Exception {
        call(put("/projects/" + projectId + "/workers/" + worker + "/shifts"), Map.of("shifts", List.of(Map.of("shiftStart", "09:00",
            "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))).andExpect(status().isOk());
    }

    private String member() {
        String worker = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", worker, worker + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, worker, "editor");
        return worker;
    }

    private void finishRun() throws Exception {
        call(post("/production-runs/" + openRun + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
        openRun = null;
    }

    private ResultActions suggestion(String orderId) throws Exception {
        return call(get("/work-orders/" + orderId + "/plan-suggestion").param("from", MONDAY));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body)));
    }
}
