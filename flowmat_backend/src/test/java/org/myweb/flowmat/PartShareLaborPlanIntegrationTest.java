package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
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
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The step plan's labor is done by each person at their share while they work, and people on an order for part of their time
 * still place it without equipment (docs/domain/worker-calendar.md LB27), on real Postgres.
 */
@AutoConfigureMockMvc
class PartShareLaborPlanIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void laborCountsEachShareWhileThatPersonWorks() throws Exception {
        String kim = member();
        String lee = member();
        String park = member();
        String idle = member();
        shifts(kim, "09:00", "17:00");
        shifts(lee, "13:00", "21:00");
        shifts(park, "09:00", "17:00");
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String workflow = data(callAs(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Part share " + tag)))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");

        // Kim for all of their time from 9, lee for half of theirs from 13: 240 worker min by 13, then 1.5 a minute to 15.
        String order = create("Shared", 10, workflow);
        stepPlan(order, mix, 240, bake, 180);
        crew(order, Map.of("userId", kim, "sharePercent", 100), Map.of("userId", lee, "sharePercent", 50));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.equipmentId").isEmpty())
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.laborMinutes").value(420))
            .andExpect(jsonPath("$.data.laborHours").value(6.0))
            .andExpect(jsonPath("$.data.neededHours").value(6.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 15)));

        // Park and lee, each for half of their time and no one for all of it: whenever either works, 9 to 21, at their shares.
        crew(order, Map.of("userId", park, "sharePercent", 50), Map.of("userId", lee, "sharePercent", 50));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.laborHours").value(10.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 19)))
            .andExpect(jsonPath("$.data.crew[0].sharePercent").value(50));

        // No one on the crew has shifts: when it runs is unknown.
        crew(order, Map.of("userId", idle, "sharePercent", 50));
        refused(order, at(6, 9), containsString("has no one with shifts, so when it runs is unknown; assign equipment or set their shifts."));

        // Lee for half of their time does not get through 100 000 worker min in a year.
        crew(order, Map.of("userId", lee, "sharePercent", 50));
        stepPlan(order, mix, 100000);
        refused(order, at(6, 9), containsString("cannot work the 100000 worker min left of its step plan in the 366 days from"));

        // A press that always runs: park, on it for half of their time, fixes nothing (WC39), but the labor waits for park:
        // 240 worker min on Monday and 180 on Tuesday by 15, 30 h of the press's time against 1 h of production.
        String press = data(callAs(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "PSP-" + tag,
            "equipmentName", "share press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))))
            .path("equipmentId").asText();
        String pressed = create("Pressed", 10, workflow);
        callAs(put("/work-orders/" + pressed + "/equipment"), json(Map.of("equipmentId", press))).andExpect(status().isOk());
        stepPlan(pressed, mix, 240, bake, 180);
        crew(pressed, Map.of("userId", park, "sharePercent", 50));
        suggestion(pressed, at(6, 9))
            .andExpect(jsonPath("$.data.productionHours").value(1.0))
            .andExpect(jsonPath("$.data.laborHours").value(30.0))
            .andExpect(jsonPath("$.data.neededHours").value(30.0))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 15)));

        shifts(kim);
        shifts(lee);
        shifts(park);
    }

    /** 2041-05-06 is a Monday; the demo project plans in Asia/Seoul (+09:00). */
    private static String at(int day, int hour) {
        return String.format("2041-05-%02dT%02d:00:00+09:00", day, hour);
    }

    private ResultActions suggestion(String workOrderId, String from) throws Exception {
        return callAs(get("/work-orders/" + workOrderId + "/plan-suggestion").param("from", from), null).andExpect(status().isOk());
    }

    private void refused(String workOrderId, String from, Matcher<String> message) throws Exception {
        callAs(get("/work-orders/" + workOrderId + "/plan-suggestion").param("from", from), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", message));
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

    /** From the first time to the second, Monday to Friday; no times clear them. */
    private void shifts(String userId, String... startAndEnd) throws Exception {
        List<Map<String, Object>> shifts = startAndEnd.length == 0 ? List.of()
            : List.of(Map.of("shiftStart", startAndEnd[0], "shiftEnd", startAndEnd[1], "workDays", List.of(1, 2, 3, 4, 5)));
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
