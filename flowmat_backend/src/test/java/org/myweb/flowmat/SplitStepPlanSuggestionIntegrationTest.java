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

/**
 * The plan suggestion's labor with a step plan split among people: each named person on the crew does their own part first
 * (docs/domain/worker-calendar.md LB33, docs/architecture/adr/ADR-023-labor-step-followups.md decisions 14-16), on real Postgres.
 */
@AutoConfigureMockMvc
class SplitStepPlanSuggestionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void namedPeopleDoTheirOwnPartsAndTheCrewSharesTheRest() throws Exception {
        String kim = member();
        String lee = member();
        String park = member();
        shifts(kim, "09:00", "17:00");
        shifts(lee, "09:00", "17:00");
        shifts(park, "09:00", "17:00");
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String workflow = data(callAs(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Split suggestion " + tag)))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String bake = process(workflow, "Bake");
        String order = create("Split", workflow);
        crew(order, Map.of("userId", kim, "sharePercent", 100), Map.of("userId", lee, "sharePercent", 100));

        // Mix is kim's 300 and lee's 60: lee is done at 10 and kim at 14, not at 12 as the two together would be.
        plan(order, split(mix, kim, 300, lee, 60));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.laborMinutes").value(360))
            .andExpect(jsonPath("$.data.laborHours").value(5.0))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 14)));

        // Bake's 120 for anyone: lee does it from 10 to 12 while kim is on their part; still 14 (not 13 all together).
        plan(order, split(mix, kim, 300, lee, 60), Map.of("nodeId", bake, "plannedMinutes", 120));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.laborMinutes").value(480))
            .andExpect(jsonPath("$.data.laborHours").value(5.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 14)));

        // A part for park, who is not on the crew, is the crew's: 120 together by 10.
        plan(order, split(mix, park, 120));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.laborMinutes").value(120))
            .andExpect(jsonPath("$.data.laborHours").value(1.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 10)))
            // Shared by the crew alone: no one's own schedule (LB35).
            .andExpect(jsonPath("$.data.schedule.length()").value(0));

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

    /** A process split among people, given as user id and minutes in turn. */
    private static Map<String, Object> split(String nodeId, Object... peopleAndMinutes) {
        List<Map<String, Object>> people = new ArrayList<>();
        for (int index = 0; index < peopleAndMinutes.length; index += 2) {
            people.add(Map.of("userId", peopleAndMinutes[index], "plannedMinutes", peopleAndMinutes[index + 1]));
        }
        return Map.of("nodeId", nodeId, "people", people);
    }

    @SafeVarargs
    private void plan(String workOrderId, Map<String, Object>... steps) throws Exception {
        callAs(put("/work-orders/" + workOrderId + "/step-plan"), json(Map.of("steps", List.of(steps)))).andExpect(status().isOk());
    }

    private String process(String workflowId, String name) throws Exception {
        return data(callAs(post("/processes"), json(Map.of("workflowId", workflowId, "processName", name, "nodeType", "process"))))
            .path("processId").asText();
    }

    private String create(String title, String workflowId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", workflowId);
        body.put("targetQuantity", 10);
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
