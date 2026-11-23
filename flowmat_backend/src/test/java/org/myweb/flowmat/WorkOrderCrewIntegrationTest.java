package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The people a work order needs, checked against their calendars (docs/domain/worker-calendar.md WC11-WC16). */
@AutoConfigureMockMvc
class WorkOrderCrewIntegrationTest extends IntegrationTestSupport {

    // 2041-03-04 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY_NINE = "2041-03-04T09:00:00+09:00";
    private static final String TUESDAY_FIVE = "2041-03-05T17:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theCrewIsNamedAndReadinessAndSuggestionsCheckTheirTime() throws Exception {
        String kim = member("editor");
        String lee = member("editor");
        String viewer = member("viewer");
        shifts(kim, List.of(shift("09:00", "17:00", 1, 2, 3, 4, 5)));
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        // No calendar: the equipment is always available, so only the crew's time matters here.
        String press = data(callAs(DEMO_OWNER, post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "CREW-" + tag,
            "equipmentName", "crew press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10))))).path("equipmentId").asText();
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", "Crew " + tag);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetQuantity", 10);
        body.put("plannedStartAt", MONDAY_NINE);
        body.put("plannedEndAt", TUESDAY_FIVE);
        String order = data(callAs(DEMO_OWNER, post("/work-orders"), json(body))).path("workOrderId").asText();

        // No crew named: no crew check.
        assertTrue(crewChecks(order).isEmpty());
        crew(DEMO_OWNER, order, List.of(kim, lee)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.members.length()").value(2))
            .andExpect(jsonPath("$.data.members[0].userId").value(kim))
            .andExpect(jsonPath("$.data.members[0].calendarSet").value(true))
            .andExpect(jsonPath("$.data.members[0].hoursInWindow").value(16))
            .andExpect(jsonPath("$.data.members[1].calendarSet").value(false));
        // Monday 9 to Tuesday 17: kim works 16 h; lee has no shifts, which is a warning, not a refusal.
        JsonNode check = crewChecks(order).get(0);
        assertEquals("warn", check.path("status").asText());
        assertEquals("Crew in the planned window: " + kim + " 16 h, " + lee + " has no shifts set.", check.path("message").asText());

        // Lee works days but is off Monday and Tuesday.
        shifts(lee, List.of(shift("09:00", "17:00", 1, 2, 3, 4, 5)));
        String dayOff = data(callAs(DEMO_OWNER, post(workers() + "/" + lee + "/days-off"),
            json(Map.of("firstDay", "2041-03-04", "lastDay", "2041-03-05")))).path("daysOff").get(0).path("dayOffId").asText();
        assertEquals("Crew in the planned window: " + kim + " 16 h, " + lee + " does not work in the planned window.",
            crewChecks(order).get(0).path("message").asText());
        callAs(DEMO_OWNER, delete(workers() + "/" + lee + "/days-off/" + dayOff), null).andExpect(status().isOk());
        check = crewChecks(order).get(0);
        assertEquals("ok", check.path("status").asText());
        assertEquals("Crew in the planned window: " + kim + " 16 h, " + lee + " 16 h.", check.path("message").asText());

        // A plan suggestion says the crew's hours in its own window: 10 at 10 an hour is Monday 9 to 10.
        callAs(DEMO_OWNER, put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", press))).andExpect(status().isOk());
        callAs(DEMO_OWNER, get("/work-orders/" + order + "/plan-suggestion").param("from", MONDAY_NINE), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.plannedEndAt").value("2041-03-04T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.crew.length()").value(2))
            .andExpect(jsonPath("$.data.crew[0].userId").value(kim))
            .andExpect(jsonPath("$.data.crew[0].calendarSet").value(true))
            .andExpect(jsonPath("$.data.crew[0].hours").value(1));

        crew(DEMO_OWNER, order, List.of(kim, "nobody-here")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("nobody-here is not in this project."));
        crew(DEMO_OWNER, order, List.of(kim, kim)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Name each person once."));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add("person-" + i);
        }
        crew(DEMO_OWNER, order, many).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A work order can name at most 20 people."));
        callAs(DEMO_OWNER, put("/work-orders/" + order + "/crew"), "{\"userIds\":\"kim\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Send userIds as a list of user ids, or members as a list of userId and sharePercent."));
        crew(viewer, order, List.of(kim)).andExpect(status().isForbidden());
        callAs("crew-outsider", get("/work-orders/" + order + "/crew"), null).andExpect(status().isForbidden());
        callAs(viewer, get("/work-orders/" + order + "/crew"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.members.length()").value(2));

        // Taking lee off keeps kim; an empty list names nobody and the check goes.
        crew(kim, order, List.of(kim)).andExpect(status().isOk()).andExpect(jsonPath("$.data.members.length()").value(1));
        crew(DEMO_OWNER, order, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.members.length()").value(0));
        assertTrue(crewChecks(order).isEmpty());

        callAs(DEMO_OWNER, post("/work-orders/" + order + "/cancel"), "{}").andExpect(status().isOk());
        crew(DEMO_OWNER, order, List.of(kim)).andExpect(status().isConflict());
        shifts(kim, List.of());
        shifts(lee, List.of());
    }

    private List<JsonNode> crewChecks(String order) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode check : data(callAs(DEMO_OWNER, get("/work-orders/" + order + "/readiness"), null)).path("checks")) {
            if ("crew".equals(check.path("code").asText())) {
                found.add(check);
            }
        }
        return found;
    }

    private ResultActions crew(String actor, String order, List<String> userIds) throws Exception {
        return callAs(actor, put("/work-orders/" + order + "/crew"), json(Map.of("userIds", userIds)));
    }

    private void shifts(String userId, List<Map<String, Object>> shifts) throws Exception {
        callAs(DEMO_OWNER, put(workers() + "/" + userId + "/shifts"), json(Map.of("shifts", shifts))).andExpect(status().isOk());
    }

    private static String workers() {
        return "/projects/" + DEMO_PROJECT + "/workers";
    }

    private static Map<String, Object> shift(String start, String end, Integer... days) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(days));
    }

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

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
