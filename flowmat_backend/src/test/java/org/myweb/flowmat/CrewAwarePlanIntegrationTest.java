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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Plan suggestions that follow the crew's shifts and other orders (docs/domain/worker-calendar.md WC24-WC28). */
@AutoConfigureMockMvc
class CrewAwarePlanIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void suggestionsWaitForTheCrewAndMovePastTheirOtherOrders() throws Exception {
        String kim = member();
        String lee = member();
        String choi = member();
        shifts(kim, 1, 2, 3, 4, 5);
        shifts(lee, 2, 3, 4, 5);
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // Neither press has a calendar, so the equipment alone could work at any hour.
        String press = equipment("CAP-" + tag);
        String press2 = equipment("CAQ-" + tag);

        String loose = order("Loose", 10, press);
        suggestion(loose, at(6, 6))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 6)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 7)))
            .andExpect(jsonPath("$.data.waitedForCrew.length()").value(0))
            .andExpect(jsonPath("$.data.crewMovedPast.length()").value(0));

        // Kim works from 9, so the hour moves there and says it waited for kim.
        String one = order("One", 10, press);
        crew(one, kim);
        suggestion(one, at(6, 6))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(6, 10)))
            .andExpect(jsonPath("$.data.waitedForCrew[0]").value(kim));
        // Lee is off on Mondays: from Monday 10:00 kim could start, lee not until Tuesday 9.
        crew(one, kim, lee);
        suggestion(one, at(6, 10))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(7, 9)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 10)))
            .andExpect(jsonPath("$.data.waitedForCrew.length()").value(1))
            .andExpect(jsonPath("$.data.waitedForCrew[0]").value(lee));
        // Another approved order holds lee on Tuesday 9 to 12: the suggestion moves after it.
        String other = dated("Other", at(7, 9), at(7, 12));
        crew(other, lee);
        suggestion(one, at(6, 10))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(7, 12)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 13)))
            .andExpect(jsonPath("$.data.crewMovedPast[0].workOrderNumber").value(number(other)))
            .andExpect(jsonPath("$.data.crewMovedPast[0].userIds[0]").value(lee))
            .andExpect(jsonPath("$.data.waitedForCrew.length()").value(0));

        // Production counts only while the crew works: 100 at 10 an hour is kim's Monday 10-17 and Tuesday 9-12.
        String longer = order("Longer", 100, press);
        crew(longer, kim);
        suggestion(longer, at(6, 10))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 10)))
            .andExpect(jsonPath("$.data.plannedEndAt").value(at(7, 12)));

        // Someone without shifts is still held by their other orders.
        String held = dated("Held", at(6, 6), at(6, 8));
        crew(held, choi);
        String choiOrder = order("Choi", 10, press);
        crew(choiOrder, choi);
        suggestion(choiOrder, at(6, 6))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(6, 8)))
            .andExpect(jsonPath("$.data.crewMovedPast[0].userIds[0]").value(choi))
            .andExpect(jsonPath("$.data.waitedForCrew.length()").value(0));

        // In a batch an order placed first holds its crew for the next, even on other equipment.
        String first = order("First", 10, press);
        String second = order("Second", 10, press2);
        crew(first, kim);
        crew(second, kim);
        callAs(post("/work-orders/plan-suggestions"), json(Map.of("projectId", DEMO_PROJECT, "workOrderIds", List.of(first, second),
                "from", at(6, 9))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].suggestion.plannedStartAt").value(at(6, 9)))
            .andExpect(jsonPath("$.data.lines[1].suggestion.plannedStartAt").value(at(6, 10)))
            .andExpect(jsonPath("$.data.lines[1].suggestion.crewMovedPast[0].workOrderNumber").value(number(first)));

        for (String id : List.of(other, held)) {
            callAs(post("/work-orders/" + id + "/cancel"), "{}").andExpect(status().isOk());
        }
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

    private String equipment(String code) throws Exception {
        return data(callAs(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", code,
            "equipmentName", code.toLowerCase(), "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))))
            .path("equipmentId").asText();
    }

    private String order(String title, int quantity, String equipmentId) throws Exception {
        String id = create(title, quantity, null, null);
        callAs(put("/work-orders/" + id + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        return id;
    }

    /** An approved order without equipment that holds its crew in these dates. */
    private String dated(String title, String start, String end) throws Exception {
        String id = create(title, 10, start, end);
        callAs(post("/work-orders/" + id + "/approve"), null).andExpect(status().isOk());
        return id;
    }

    private String create(String title, int quantity, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetQuantity", quantity);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        return data(callAs(post("/work-orders"), json(body))).path("workOrderId").asText();
    }

    private String number(String workOrderId) throws Exception {
        return data(callAs(get("/work-orders/" + workOrderId), null)).path("workOrderNumber").asText();
    }

    private void crew(String workOrderId, String... userIds) throws Exception {
        callAs(put("/work-orders/" + workOrderId + "/crew"), json(Map.of("userIds", List.of(userIds)))).andExpect(status().isOk());
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
