package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/** Each crew member's share of an order (docs/domain/worker-calendar.md WC36-WC40) against real Postgres. */
@AutoConfigureMockMvc
class PartTimeCrewIntegrationTest extends IntegrationTestSupport {

    // 2041-07-01 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY = "2041-07-01T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-07-08T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sharesAddUpAndOnlyPast100PercentIsSomeoneBookedTwice() throws Exception {
        String kim = member();
        callAs(put("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))))
            .andExpect(status().isOk());
        String press = data(callAs(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode",
            "PTC-" + UUID.randomUUID().toString().substring(0, 8), "equipmentName", "part-time press", "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10))))).path("equipmentId").asText();

        // Half of kim on Monday-Tuesday and half on Tuesday-Wednesday: Tuesday adds up to 100%, nobody is booked twice.
        String first = dated("First", at(1, 9), at(2, 17));
        String second = dated("Second", at(2, 9), at(3, 17));
        shares(first, kim, 50).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.members[0].sharePercent").value(50))
            .andExpect(jsonPath("$.data.members[0].hoursInWindow").value(16));
        shares(second, kim, 50).andExpect(status().isOk());
        JsonNode row = worker(kim);
        assertEquals(16.0, row.path("plannedHours").asDouble());
        assertEquals(40.0, row.path("loadPercent").asDouble());
        assertEquals(0, row.path("overlaps").size());
        assertEquals(50, row.path("orders").get(0).path("sharePercent").asInt());
        assertEquals(8.0, row.path("orders").get(0).path("hoursInWindow").asDouble());
        assertEquals("Crew in the planned window: " + kim + " 50% of 16 h.", check(first, "crew").path("message").asText());
        assertTrue(check(first, "crew_schedule").isMissingNode());

        // A fifth of kim on Tuesday too: 120%, so every pair of the three is at once on Tuesday 9-17.
        String third = dated("Third", at(2, 9), at(2, 17));
        shares(third, kim, 20).andExpect(status().isOk());
        row = worker(kim);
        assertEquals(17.6, row.path("plannedHours").asDouble());
        assertEquals(44.0, row.path("loadPercent").asDouble());
        assertEquals(3, row.path("overlaps").size());
        assertEquals(8.0, row.path("overlaps").get(0).path("hours").asDouble());
        assertEquals("Also booked at the same time: " + kim + " on " + number(first) + " for 8 h, " + kim + " on " + number(second)
            + " for 8 h.", check(third, "crew_schedule").path("message").asText());

        // A suggestion needing 30% of kim fits beside the second order's 50% on Wednesday; 60% does not and moves after it.
        // Below 100% kim does not fix the hours, so the press, always available, can run at 17:00.
        String fourth = order("Fourth", press);
        shares(fourth, kim, 30).andExpect(status().isOk());
        suggestion(fourth, at(3, 9))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(3, 9)))
            .andExpect(jsonPath("$.data.crewMovedPast.length()").value(0))
            .andExpect(jsonPath("$.data.crew[0].sharePercent").value(30));
        shares(fourth, kim, 60).andExpect(status().isOk());
        suggestion(fourth, at(3, 9))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(3, 17)))
            .andExpect(jsonPath("$.data.crewMovedPast[0].workOrderNumber").value(number(second)))
            .andExpect(jsonPath("$.data.crewMovedPast[0].userIds[0]").value(kim));
        // All of kim: after the second order kim works again on Thursday 9.
        shares(fourth, kim, 100).andExpect(status().isOk());
        suggestion(fourth, at(3, 9))
            .andExpect(jsonPath("$.data.plannedStartAt").value(at(4, 9)))
            .andExpect(jsonPath("$.data.waitedForCrew[0]").value(kim))
            .andExpect(jsonPath("$.data.crewMovedPast[0].workOrderNumber").value(number(second)));

        callAs(put("/work-orders/" + first + "/crew"), json(Map.of("userIds", List.of(kim),
                "members", List.of(Map.of("userId", kim, "sharePercent", 50)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Send userIds or members, not both."));
        shares(first, kim, 0).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("sharePercent must be a whole number from 1 to 100."));
        shares(first, kim, 101).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("sharePercent must be a whole number from 1 to 100."));
        callAs(put("/work-orders/" + first + "/crew"), "{\"members\":[{\"userId\":\"" + kim + "\",\"sharePercent\":50.5}]}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Send userIds as a list of user ids, or members as a list of userId and sharePercent."));
        callAs(put("/work-orders/" + first + "/crew"), "{\"members\":[{\"userId\":\"" + kim + "\",\"sharePercent\":\"50\"}]}")
            .andExpect(status().isBadRequest());
        callAs(put("/work-orders/" + first + "/crew"), json(Map.of("members", List.of(Map.of("sharePercent", 50)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Name each person by their user id."));
        // userIds still names people for all of their time.
        callAs(put("/work-orders/" + first + "/crew"), json(Map.of("userIds", List.of(kim)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.members[0].sharePercent").value(100));

        for (String id : List.of(first, second, third)) {
            callAs(post("/work-orders/" + id + "/cancel"), "{}").andExpect(status().isOk());
        }
        callAs(put("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/shifts"), json(Map.of("shifts", List.of())))
            .andExpect(status().isOk());
    }

    private static String at(int day, int hour) {
        return String.format("2041-07-%02dT%02d:00:00+09:00", day, hour);
    }

    private ResultActions shares(String workOrderId, String userId, int share) throws Exception {
        return callAs(put("/work-orders/" + workOrderId + "/crew"),
            json(Map.of("members", List.of(Map.of("userId", userId, "sharePercent", share)))));
    }

    private ResultActions suggestion(String workOrderId, String from) throws Exception {
        return callAs(get("/work-orders/" + workOrderId + "/plan-suggestion").param("from", from), null).andExpect(status().isOk());
    }

    private JsonNode worker(String userId) throws Exception {
        JsonNode load = data(callAs(get("/worker-load").param("projectId", DEMO_PROJECT).param("from", MONDAY)
            .param("to", NEXT_MONDAY), null));
        for (JsonNode row : load.path("workers")) {
            if (userId.equals(row.path("userId").asText())) {
                return row;
            }
        }
        throw new AssertionError(userId + " is not on the board");
    }

    private JsonNode check(String workOrderId, String code) throws Exception {
        for (JsonNode check : data(callAs(get("/work-orders/" + workOrderId + "/readiness"), null)).path("checks")) {
            if (code.equals(check.path("code").asText())) {
                return check;
            }
        }
        return objectMapper.missingNode();
    }

    /** An approved order without equipment that holds its crew in these dates. */
    private String dated(String title, String start, String end) throws Exception {
        String id = create(title, start, end);
        callAs(post("/work-orders/" + id + "/approve"), null).andExpect(status().isOk());
        return id;
    }

    private String order(String title, String equipmentId) throws Exception {
        String id = create(title, null, null);
        callAs(put("/work-orders/" + id + "/equipment"), json(Map.of("equipmentId", equipmentId))).andExpect(status().isOk());
        return id;
    }

    private String create(String title, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetQuantity", 10);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        return data(callAs(post("/work-orders"), json(body))).path("workOrderId").asText();
    }

    private String number(String workOrderId) throws Exception {
        return data(callAs(get("/work-orders/" + workOrderId), null)).path("workOrderNumber").asText();
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
