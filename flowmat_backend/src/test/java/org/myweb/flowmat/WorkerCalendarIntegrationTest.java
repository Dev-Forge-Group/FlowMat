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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Workers' calendars (docs/domain/worker-calendar.md WC1-WC10) against real Postgres. */
@AutoConfigureMockMvc
class WorkerCalendarIntegrationTest extends IntegrationTestSupport {

    // 2041-03-04 is a Monday; the demo project plans in Asia/Seoul (+09:00). Far enough out to share no holiday with others.
    private static final String MONDAY = "2041-03-04T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-03-11T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerCalendarQuery workerCalendars;

    @Test
    void theOwnerAndEachWorkerSetShiftsDaysOffAndTheTimeAddsUp() throws Exception {
        String worker = member("editor");
        String other = member("editor");
        String viewer = member("viewer");

        // Everyone who can read the project is a worker, the owner first; without shifts their time is not known.
        List<JsonNode> listed = list(data(callAs(DEMO_OWNER, get(workers()), null)));
        assertEquals(DEMO_OWNER, listed.get(0).path("userId").asText());
        assertTrue(listed.get(0).path("owner").asBoolean());
        JsonNode fresh = find(listed, worker);
        assertFalse(fresh.path("calendarSet").asBoolean());
        assertEquals(0.0, fresh.path("weeklyHours").asDouble());

        // The owner gives a day shift Monday to Friday: 40 hours a week.
        shifts(DEMO_OWNER, worker, List.of(shift("09:00", "17:00", 1, 2, 3, 4, 5))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.calendarSet").value(true))
            .andExpect(jsonPath("$.data.weeklyHours").value(40))
            .andExpect(jsonPath("$.data.shifts[0].shiftStart").value("09:00"))
            .andExpect(jsonPath("$.data.shifts[0].workDays.length()").value(5));
        // The worker adds a Friday night shift to their own calendar; nobody else but the owner may change it.
        shifts(worker, worker, List.of(shift("09:00", "17:00", 1, 2, 3, 4, 5), shift("22:00", "06:00", 5)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.weeklyHours").value(48));
        shifts(other, worker, List.of(shift("09:00", "17:00", 1))).andExpect(status().isForbidden());
        shifts(viewer, viewer, List.of(shift("09:00", "17:00", 1))).andExpect(status().isForbidden());

        shifts(DEMO_OWNER, worker, List.of(shift("09:00", "17:00", 1), shift("16:00", "20:00", 1))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap on Mon."));
        shifts(DEMO_OWNER, worker, List.of(shift("09:00", "17:00", 8))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("workDays are ISO days of the week, 1 (Monday) to 7 (Sunday)."));
        shifts(DEMO_OWNER, worker, List.of(shift("9am", "17:00", 1))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("shiftStart must be a time such as 08:30."));
        List<Map<String, Object>> seven = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            seven.add(shift("0" + day + ":00", "0" + day + ":30", day));
        }
        shifts(DEMO_OWNER, worker, seven).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A worker can have at most 6 shifts."));
        shifts(DEMO_OWNER, "nobody-here", List.of(shift("09:00", "17:00", 1))).andExpect(status().isNotFound());
        callAs("worker-outsider", get(workers()), null).andExpect(status().isForbidden());

        // Mon-Fri 9-17 and the Friday night: 48 hours in the week. Wednesday off takes 8, a Thursday holiday 8 more.
        assertEquals(48.0, available(worker).path("workingHours").asDouble());
        JsonNode withDayOff = data(dayOff(worker, worker, "2041-03-06", "2041-03-06", "Training"));
        String dayOffId = withDayOff.path("daysOff").get(0).path("dayOffId").asText();
        assertEquals("Training", withDayOff.path("daysOff").get(0).path("reason").asText());
        assertEquals(40.0, available(worker).path("workingHours").asDouble());
        JsonNode added = data(callAs(DEMO_OWNER, post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", "2041-03-07",
            "name", "Worker calendar test"))));
        String holidayId = null;
        for (JsonNode holiday : added) {
            if ("2041-03-07".equals(holiday.path("date").asText())) {
                holidayId = holiday.path("holidayId").asText();
            }
        }
        JsonNode week = available(worker);
        assertEquals(32.0, week.path("workingHours").asDouble());
        assertEquals("2041-03-06", week.path("daysOff").get(0).asText());
        assertEquals("2041-03-07", week.path("holidays").get(0).asText());

        // The same through the public query, for a worker with and one without shifts.
        Map<String, WorkerHours> hours = workerCalendars.workingHours(DEMO_PROJECT, List.of(worker, other),
            OffsetDateTime.parse(MONDAY), OffsetDateTime.parse(NEXT_MONDAY));
        assertEquals(32.0, hours.get(worker).hours().doubleValue());
        assertTrue(hours.get(worker).calendarSet());
        assertFalse(hours.get(other).calendarSet());

        dayOff(worker, worker, "2041-03-09", "2041-03-01", null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("lastDay must not be before firstDay."));
        dayOff(worker, worker, "2041-01-01", "2042-03-01", null).andExpect(status().isBadRequest());
        dayOff(worker, worker, "2041-03-09", null, "x".repeat(201)).andExpect(status().isBadRequest());
        dayOff(other, worker, "2041-03-09", null, null).andExpect(status().isForbidden());
        callAs(worker, get(workers() + "/" + worker + "/availability").param("from", NEXT_MONDAY).param("to", MONDAY), null)
            .andExpect(status().isBadRequest());

        callAs(worker, delete(workers() + "/" + worker + "/days-off/" + dayOffId), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.daysOff.length()").value(0));
        callAs(worker, delete(workers() + "/" + worker + "/days-off/" + dayOffId), null).andExpect(status().isNotFound());
        callAs(DEMO_OWNER, delete("/holidays/" + holidayId), null).andExpect(status().isOk());
        assertEquals(48.0, available(worker).path("workingHours").asDouble());
        shifts(worker, worker, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.data.calendarSet").value(false));
    }

    private static String workers() {
        return "/projects/" + DEMO_PROJECT + "/workers";
    }

    private JsonNode available(String userId) throws Exception {
        return data(callAs(DEMO_OWNER, get(workers() + "/" + userId + "/availability").param("from", MONDAY).param("to", NEXT_MONDAY),
            null));
    }

    private ResultActions shifts(String actor, String userId, List<Map<String, Object>> shifts) throws Exception {
        return callAs(actor, put(workers() + "/" + userId + "/shifts"), json(Map.of("shifts", shifts)));
    }

    private ResultActions dayOff(String actor, String userId, String first, String last, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstDay", first);
        body.put("lastDay", last);
        body.put("reason", reason);
        return callAs(actor, post(workers() + "/" + userId + "/days-off"), json(body));
    }

    private static Map<String, Object> shift(String start, String end, Integer... days) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(days));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> found = new ArrayList<>();
        array.forEach(found::add);
        return found;
    }

    private static JsonNode find(List<JsonNode> workers, String userId) {
        return workers.stream().filter(one -> userId.equals(one.path("userId").asText())).findFirst().orElseThrow();
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
