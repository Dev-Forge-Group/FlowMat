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
 * The plan suggestion with a split step plan follows the order of the workflow's processes: a process starts once the ones before
 * it are done (docs/domain/worker-calendar.md LB34, docs/architecture/adr/ADR-023-labor-step-followups.md decisions 17-19), on
 * real Postgres.
 */
@AutoConfigureMockMvc
class OrderedSplitPlanSuggestionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void laterProcessesWaitForTheOnesBeforeThem() throws Exception {
        String kim = member();
        String lee = member();
        shifts(kim, "09:00", "17:00");
        shifts(lee, "09:00", "17:00");
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // Mix → Cut → Bake; Cut has no plan.
        String workflow = data(callAs(post("/workflows"), json(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Ordered split " + tag)))).path("workflowId").asText();
        String mix = process(workflow, "Mix");
        String cut = process(workflow, "Cut");
        String bake = process(workflow, "Bake");
        connect(workflow, mix, cut);
        connect(workflow, cut, bake);
        String order = create("Ordered", workflow);
        crew(order, Map.of("userId", kim, "sharePercent", 100), Map.of("userId", lee, "sharePercent", 100));

        // Mix is kim's 300 and Bake lee's 60: lee waits for Mix until 14 and is done at 15 (at 14 without the order).
        plan(order, split(mix, kim, 300), split(bake, lee, 60));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.laborMinutes").value(360))
            .andExpect(jsonPath("$.data.laborHours").value(6.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 15)))
            // Who works when (LB35): kim on Mix to 14, then lee on Bake.
            .andExpect(jsonPath("$.data.schedule.length()").value(2))
            .andExpect(jsonPath("$.data.schedule[0].nodeName").value("Mix"))
            .andExpect(jsonPath("$.data.schedule[0].userId").value(kim))
            .andExpect(jsonPath("$.data.schedule[0].from").value(at(6, 9)))
            .andExpect(jsonPath("$.data.schedule[0].to").value(at(6, 14)))
            .andExpect(jsonPath("$.data.schedule[1].nodeName").value("Bake"))
            .andExpect(jsonPath("$.data.schedule[1].userId").value(lee))
            .andExpect(jsonPath("$.data.schedule[1].from").value(at(6, 14)))
            .andExpect(jsonPath("$.data.schedule[1].to").value(at(6, 15)));

        // Mix's 120 for anyone: both on it to 10, then lee's Bake to 11 (10:30 without the order).
        plan(order, Map.of("nodeId", mix, "plannedMinutes", 120), split(bake, lee, 60));
        suggestion(order, at(6, 9))
            .andExpect(jsonPath("$.data.laborMinutes").value(180))
            .andExpect(jsonPath("$.data.laborHours").value(2.0))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 11)))
            // Both on Mix to 10, then lee on Bake to 11.
            .andExpect(jsonPath("$.data.schedule.length()").value(3))
            .andExpect(jsonPath("$.data.schedule[0].to").value(at(6, 10)))
            .andExpect(jsonPath("$.data.schedule[1].to").value(at(6, 10)))
            .andExpect(jsonPath("$.data.schedule[2].nodeName").value("Bake"))
            .andExpect(jsonPath("$.data.schedule[2].from").value(at(6, 10)))
            .andExpect(jsonPath("$.data.schedule[2].to").value(at(6, 11)));

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

    private void connect(String workflowId, String from, String to) throws Exception {
        callAs(post("/process-connections"), json(Map.of("workflowId", workflowId, "fromProcessId", from, "toProcessId", to)))
            .andExpect(status().isOk());
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
