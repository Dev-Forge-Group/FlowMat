package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** The plan suggestion counts what is left of the step plan, shared by the crew (docs/domain/worker-calendar.md LB25), on real Postgres. */
@AutoConfigureMockMvc
class LaborPlanSuggestionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theStepPlanSaysHowLongAnOrderTakes() throws Exception {
        String kim = member();
        String lee = member();
        shifts(kim, 1, 2, 3, 4, 5);
        shifts(lee, 1, 2, 3, 4, 5);
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String workflow = data(callAs(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Labor plan " + tag)))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");

        // Without equipment or a step plan with a crew, the dates follow equipment as before.
        String loose = create("Loose", 10, workflow);
        refused(loose, at(6, 9), "Assign equipment first; the dates follow its calendar.");
        stepPlan(loose, mix, 240, bake, 180);
        refused(loose, at(6, 9), "Assign equipment first; the dates follow its calendar.");

        // Kim for all of their time: 420 worker minutes are 7 h of kim's Monday from 9.
        crew(loose, Map.of("userId", kim, "sharePercent", 100));
        suggestion(loose, at(6, 9))
            .andExpect(jsonPath("$.data.equipmentId").isEmpty())
            .andExpect(jsonPath("$.data.productionHours").isEmpty())
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 16)))
            .andExpect(jsonPath("$.data.laborMinutes").value(420))
            .andExpect(jsonPath("$.data.laborHours").value(7.0))
            .andExpect(jsonPath("$.data.neededHours").value(7.0))
            .andExpect(jsonPath("$.data.crew[0].userId").value(kim));
        // Lee joins for half of their time: 1.5 people share it, and kim alone fixes when (WC39).
        crew(loose, Map.of("userId", kim, "sharePercent", 100), Map.of("userId", lee, "sharePercent", 50));
        suggestion(loose, at(6, 9))
            .andExpect(jsonPath("$.data.laborHours").value(4.67))
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-05-06T13:41:00+09:00"));
        // Lee alone, for half of their time, does it at half pace in their own time (LB27): 14 h of Monday and Tuesday.
        crew(loose, Map.of("userId", lee, "sharePercent", 50));
        suggestion(loose, at(6, 9))
            .andExpect(jsonPath("$.data.laborHours").value(14.0))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 15)));

        // With equipment the longer decides: 10 at 10 an hour is 1 h, the plan 7 h with kim.
        String press = data(callAs(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "LBP-" + tag,
            "equipmentName", "labor press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))))
            .path("equipmentId").asText();
        String pressed = create("Pressed", 10, workflow);
        callAs(put("/work-orders/" + pressed + "/equipment"), json(Map.of("equipmentId", press))).andExpect(status().isOk());
        crew(pressed, Map.of("userId", kim, "sharePercent", 100));
        suggestion(pressed, at(7, 9))
            .andExpect(jsonPath("$.data.laborMinutes").isEmpty())
            .andExpect(jsonPath("$.data.neededHours").value(1.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 10)));
        stepPlan(pressed, mix, 240, bake, 180);
        suggestion(pressed, at(7, 9))
            .andExpect(jsonPath("$.data.productionHours").value(1.0))
            .andExpect(jsonPath("$.data.laborHours").value(7.0))
            .andExpect(jsonPath("$.data.neededHours").value(7.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 16)));
        // A plan shorter than production leaves it to the equipment.
        stepPlan(pressed, mix, 30);
        suggestion(pressed, at(7, 9))
            .andExpect(jsonPath("$.data.laborHours").value(0.5))
            .andExpect(jsonPath("$.data.neededHours").value(1.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 10)));

        shifts(kim);
        shifts(lee);
    }

    /** 2041-05-06 is a Monday; the demo project plans in Asia/Seoul (+09:00). */
    private static String at(int day, int hour) {
        return String.format("2041-05-%02dT%02d:00:00+09:00", day, hour);
    }

    private ResultActions suggestion(String workOrderId, String from) throws Exception {
        return callAs(get("/work-orders/" + workOrderId + "/plan-suggestion").param("from", from), null).andExpect(status().isOk());
    }

    private void refused(String workOrderId, String from, String message) throws Exception {
        callAs(get("/work-orders/" + workOrderId + "/plan-suggestion").param("from", from), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(message));
    }

    private void stepPlan(String workOrderId, Object... nodesAndMinutes) throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int index = 0; index < nodesAndMinutes.length; index += 2) {
            steps.add(Map.of("nodeId", nodesAndMinutes[index], "plannedMinutes", nodesAndMinutes[index + 1]));
        }
        callAs(put("/work-orders/" + workOrderId + "/step-plan"), json(Map.of("steps", steps))).andExpect(status().isOk());
    }

    private String process(String workflowId, String name) throws Exception {
        return data(callAs(post("/processes"), json(Map.of("workflowId", workflowId, "processName", name, "nodeType", "process"))))
            .path("processId").asText();
    }

    private String create(String title, int quantity, String workflowId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", workflowId);
        body.put("targetQuantity", quantity);
        return data(callAs(post("/work-orders"), json(body))).path("workOrderId").asText();
    }

    @SafeVarargs
    private void crew(String workOrderId, Map<String, Object>... members) throws Exception {
        callAs(put("/work-orders/" + workOrderId + "/crew"), json(Map.of("members", List.of(members)))).andExpect(status().isOk());
    }

    /** Days 9 to 17 on the given ISO days; none clears them. */
    private void shifts(String userId, Integer... days) throws Exception {
        List<Map<String, Object>> shifts = days.length == 0 ? List.of()
            : List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(days)));
        callAs(put("/projects/" + DEMO_PROJECT + "/workers/" + userId + "/shifts"), json(Map.of("shifts", shifts)))
            .andExpect(status().isOk());
    }

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

    private ResultActions callAs(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
