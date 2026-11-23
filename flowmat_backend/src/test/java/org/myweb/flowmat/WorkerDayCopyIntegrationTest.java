package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** A worker's own dates copied to another worker (docs/domain/worker-calendar.md WC41-WC43). */
@AutoConfigureMockMvc
class WorkerDayCopyIntegrationTest extends IntegrationTestSupport {

    // 2041-07-01 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY = "2041-07-01T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-07-08T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void dayChangesCopyToAnotherWorkerReplacingThoseDatesAndKeepingTheirOthers() throws Exception {
        String lee = member("editor");
        String kim = member("editor");
        String fresh = member("editor");
        String viewer = member("viewer");
        weekly(lee);
        weekly(kim);
        // Lee: Wednesday off for stocktaking, Saturday 8-12, and a Monday 6-14 a fortnight on.
        day(lee, "2041-07-03", List.of(), "Stocktaking");
        day(lee, "2041-07-06", List.of(shift("08:00", "12:00")), null);
        day(lee, "2041-07-15", List.of(shift("06:00", "14:00")), null);
        // Kim: Wednesday 10-12 for training (to be replaced) and Thursday off (to stay).
        day(kim, "2041-07-03", List.of(shift("10:00", "12:00")), "Training");
        day(kim, "2041-07-04", List.of(), "Leave");
        assertEquals(26.0, hours(kim));

        // The first ten days of July: Wednesday off and Saturday 8-12 for Kim, whose Thursday stays; 40 - 8 - 8 + 4.
        JsonNode copied = data(copy(DEMO_OWNER, lee, kim, "2041-07-01", "2041-07-10"));
        assertEquals(kim, copied.path("userId").asText());
        Map<String, JsonNode> days = days(copied);
        assertEquals(List.of("2041-07-03", "2041-07-04", "2041-07-06"), List.copyOf(days.keySet()));
        assertTrue(days.get("2041-07-03").path("off").asBoolean());
        assertEquals("Stocktaking", days.get("2041-07-03").path("reason").asText());
        assertEquals("Leave", days.get("2041-07-04").path("reason").asText());
        assertEquals(4.0, days.get("2041-07-06").path("hours").asDouble());
        assertEquals(DEMO_OWNER, days.get("2041-07-06").path("updatedBy").asText());
        assertEquals(28.0, hours(kim));
        // Lee's own dates are unchanged.
        assertEquals(3, data(callAs(DEMO_OWNER, get(workers() + "/" + lee), null)).path("days").size());

        // Open on both sides: every one of Lee's dates, the Monday a fortnight on too.
        assertTrue(days(data(copy(DEMO_OWNER, lee, kim, null, null))).containsKey("2041-07-15"));
        // A worker may copy someone's dates to their own calendar, not to another's.
        copy(kim, lee, kim, "2041-07-15", null).andExpect(status().isOk());
        copy(kim, lee, fresh, null, null).andExpect(status().isForbidden());
        copy(viewer, lee, viewer, null, null).andExpect(status().isForbidden());
        copy("copy-outsider", lee, kim, null, null).andExpect(status().isForbidden());

        copy(DEMO_OWNER, lee, lee, null, null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose another person to copy to."));
        copy(DEMO_OWNER, lee, "nobody-" + UUID.randomUUID(), null, null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("toUserId is not a worker of this project."));
        copy(DEMO_OWNER, lee, fresh, null, null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Set " + fresh + "'s weekly shifts first; a date's shifts take the place of them."));
        copy(DEMO_OWNER, lee, kim, "2041-07-10", "2041-07-01").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("throughDate must not be before fromDate."));
        copy(DEMO_OWNER, lee, kim, "2041-7-1", null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("fromDate must be a date such as 2030-01-09."));
        copy(DEMO_OWNER, lee, kim, "2041-08-01", "2041-08-31").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("There are no day changes to copy."));
        assertFalse(days(data(callAs(DEMO_OWNER, get(workers() + "/" + fresh), null))).containsKey("2041-07-03"));

        for (String one : List.of(lee, kim)) {
            callAs(DEMO_OWNER, delete(workers() + "/" + one + "/days/2041-07-01").param("through", "2041-07-31"), null)
                .andExpect(status().isOk());
        }
    }

    private ResultActions copy(String actor, String from, String to, String fromDate, String throughDate) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("toUserId", to);
        body.put("fromDate", fromDate);
        body.put("throughDate", throughDate);
        return callAs(actor, post(workers() + "/" + from + "/days/copy"), json(body));
    }

    private void weekly(String userId) throws Exception {
        callAs(DEMO_OWNER, put(workers() + "/" + userId + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))))
            .andExpect(status().isOk());
    }

    private void day(String userId, String date, List<Map<String, Object>> shifts, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", shifts);
        body.put("reason", reason);
        callAs(DEMO_OWNER, put(workers() + "/" + userId + "/days/" + date), json(body)).andExpect(status().isOk());
    }

    private static Map<String, Object> shift(String start, String end) {
        return Map.of("shiftStart", start, "shiftEnd", end);
    }

    /** The worker's own dates by date, earliest first. */
    private static Map<String, JsonNode> days(JsonNode worker) {
        Map<String, JsonNode> days = new LinkedHashMap<>();
        worker.path("days").forEach(one -> days.put(one.path("date").asText(), one));
        return days;
    }

    private double hours(String userId) throws Exception {
        return data(callAs(DEMO_OWNER, get(workers() + "/" + userId + "/availability").param("from", MONDAY)
            .param("to", NEXT_MONDAY), null)).path("workingHours").asDouble();
    }

    private static String workers() {
        return "/projects/" + DEMO_PROJECT + "/workers";
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
