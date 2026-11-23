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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A worker's shifts for one date in place of their weekly ones (docs/domain/worker-calendar.md WC29-WC35). */
@AutoConfigureMockMvc
class WorkerDayShiftIntegrationTest extends IntegrationTestSupport {

    // 2041-06-03 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY = "2041-06-03T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-06-10T00:00:00+09:00";
    private static final String MONDAY_AFTER = "2041-06-17T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerCalendarQuery workerCalendars;

    @Test
    void aDateTakesItsOwnShiftsOverTheWeekDaysOffAndHolidays() throws Exception {
        String worker = member("editor");
        String other = member("editor");
        String viewer = member("viewer");
        String fresh = member("editor");
        callAs(DEMO_OWNER, put(workers() + "/" + worker + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))))
            .andExpect(status().isOk());
        assertEquals(40.0, hours(worker, MONDAY, NEXT_MONDAY));

        // Wednesday without work for stocktaking: 32.
        day(DEMO_OWNER, worker, "2041-06-05", List.of(), "Stocktaking", null, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].date").value("2041-06-05"))
            .andExpect(jsonPath("$.data.days[0].off").value(true))
            .andExpect(jsonPath("$.data.days[0].hours").value(0))
            .andExpect(jsonPath("$.data.days[0].reason").value("Stocktaking"));
        assertEquals(32.0, hours(worker, MONDAY, NEXT_MONDAY));
        // The worker adds Saturday overtime 8-12 themselves: 36.
        day(worker, worker, "2041-06-08", List.of(shift("08:00", "12:00")), null, null, null).andExpect(status().isOk());
        assertEquals(36.0, hours(worker, MONDAY, NEXT_MONDAY));
        // Tuesday in two shifts, 6-14 and 14-18: 12 instead of 8, so 40.
        day(DEMO_OWNER, worker, "2041-06-04", List.of(shift("06:00", "14:00"), shift("14:00", "18:00")), null, null, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].date").value("2041-06-04"))
            .andExpect(jsonPath("$.data.days[0].hours").value(12))
            .andExpect(jsonPath("$.data.days[0].shifts[1].shiftStart").value("14:00"));
        assertEquals(40.0, hours(worker, MONDAY, NEXT_MONDAY));

        // A Thursday holiday takes 8; the date's own 10-12 beats it, and the holiday is no longer listed for the worker.
        String holidayId = holiday("2041-06-06");
        assertEquals(32.0, hours(worker, MONDAY, NEXT_MONDAY));
        day(DEMO_OWNER, worker, "2041-06-06", List.of(shift("10:00", "12:00")), null, null, null).andExpect(status().isOk());
        JsonNode week = availability(worker, MONDAY, NEXT_MONDAY);
        assertEquals(34.0, week.path("workingHours").asDouble());
        assertEquals(0, week.path("holidays").size());
        // A Friday day off takes 8; a date's own 9-11 beats it too.
        String dayOffId = data(callAs(DEMO_OWNER, post(workers() + "/" + worker + "/days-off"), json(Map.of("firstDay", "2041-06-07"))))
            .path("daysOff").get(0).path("dayOffId").asText();
        assertEquals(26.0, hours(worker, MONDAY, NEXT_MONDAY));
        day(DEMO_OWNER, worker, "2041-06-07", List.of(shift("09:00", "11:00")), null, null, null).andExpect(status().isOk());
        week = availability(worker, MONDAY, NEXT_MONDAY);
        assertEquals(28.0, week.path("workingHours").asDouble());
        assertEquals(0, week.path("daysOff").size());
        // The other public answer counts the same.
        assertEquals(0, new BigDecimal("28").compareTo(workerCalendars.workingHours(DEMO_PROJECT, List.of(worker),
            OffsetDateTime.parse(MONDAY), OffsetDateTime.parse(NEXT_MONDAY)).get(worker).hours()));

        // Mondays only from 2041-06-10 through 2041-06-23 get 9-13: the next week is 36.
        day(DEMO_OWNER, worker, "2041-06-10", List.of(shift("09:00", "13:00")), "Short Mondays", "2041-06-23", List.of(1))
            .andExpect(status().isOk());
        assertEquals(36.0, hours(worker, NEXT_MONDAY, MONDAY_AFTER));
        List<String> dates = new ArrayList<>();
        data(callAs(DEMO_OWNER, get(workers() + "/" + worker), null)).path("days").forEach(one -> dates.add(one.path("date").asText()));
        assertEquals(List.of("2041-06-04", "2041-06-05", "2041-06-06", "2041-06-07", "2041-06-08", "2041-06-10", "2041-06-17"), dates);

        day(DEMO_OWNER, worker, "2041-06-04", List.of(shift("09:00", "17:00"), shift("16:00", "20:00")), null, null, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap."));
        List<Map<String, Object>> seven = new ArrayList<>();
        for (int hour = 0; hour < 7; hour++) {
            seven.add(shift(String.format("%02d:00", hour), String.format("%02d:30", hour)));
        }
        day(DEMO_OWNER, worker, "2041-06-04", seven, null, null, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("A day can have at most 6 shifts."));
        day(DEMO_OWNER, worker, "2041-06-04", List.of(shift("9am", "17:00")), null, null, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("shiftStart must be a time such as 08:30."));
        day(DEMO_OWNER, worker, "2041-6-4", List.of(), null, null, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("date must be a date such as 2030-01-09."));
        day(DEMO_OWNER, worker, "2041-06-04", List.of(), null, "2041-06-03", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("throughDate must not be before the date."));
        day(DEMO_OWNER, worker, "2041-06-03", List.of(), null, "2041-08-04", null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("A range can cover at most 62 days."));
        day(DEMO_OWNER, worker, "2041-06-03", List.of(), null, "2041-06-04", List.of(8))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("weekDays are 1 (Monday) to 7 (Sunday)."));
        day(DEMO_OWNER, worker, "2041-06-03", List.of(), null, "2041-06-04", List.of(7))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("None of the dates fall on the chosen days of the week."));
        day(DEMO_OWNER, worker, "2041-06-04", List.of(), "x".repeat(201), null, null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("reason must be at most 200 characters."));
        day(DEMO_OWNER, fresh, "2041-06-04", List.of(), null, null, null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Set " + fresh + "'s weekly shifts first; a date's shifts take the place of them."));
        day(other, worker, "2041-06-04", List.of(), null, null, null).andExpect(status().isForbidden());
        day(viewer, viewer, "2041-06-04", List.of(), null, null, null).andExpect(status().isForbidden());
        day("days-outsider", worker, "2041-06-04", List.of(), null, null, null).andExpect(status().isForbidden());

        // Saturday back to the week: 24. Tuesday to Thursday back at once: Tuesday and Wednesday 8 each, Thursday a holiday.
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days/2041-06-08"), null).andExpect(status().isOk());
        assertEquals(24.0, hours(worker, MONDAY, NEXT_MONDAY));
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days/2041-06-04").param("through", "2041-06-06"), null)
            .andExpect(status().isOk());
        assertEquals(26.0, hours(worker, MONDAY, NEXT_MONDAY));
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days/2041-06-04"), null).andExpect(status().isNotFound());
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days/2041-06-04").param("through", "2041-06-03"), null)
            .andExpect(status().isBadRequest());

        callAs(DEMO_OWNER, delete("/holidays/" + holidayId), null).andExpect(status().isOk());
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days-off/" + dayOffId), null).andExpect(status().isOk());
        callAs(DEMO_OWNER, delete(workers() + "/" + worker + "/days/2041-06-01").param("through", "2041-06-30"), null)
            .andExpect(status().isOk());
        assertTrue(data(callAs(DEMO_OWNER, get(workers() + "/" + worker), null)).path("days").isEmpty());
    }

    private ResultActions day(String actor, String userId, String date, List<Map<String, Object>> shifts, String reason,
                              String throughDate, List<Integer> weekDays) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", shifts);
        body.put("reason", reason);
        body.put("throughDate", throughDate);
        body.put("weekDays", weekDays);
        return callAs(actor, put(workers() + "/" + userId + "/days/" + date), json(body));
    }

    private static Map<String, Object> shift(String start, String end) {
        return Map.of("shiftStart", start, "shiftEnd", end);
    }

    private String holiday(String date) throws Exception {
        for (JsonNode holiday : data(callAs(DEMO_OWNER, post("/holidays"), json(Map.of("projectId", DEMO_PROJECT, "date", date,
            "name", "Worker day test"))))) {
            if (date.equals(holiday.path("date").asText())) {
                return holiday.path("holidayId").asText();
            }
        }
        throw new AssertionError("holiday " + date + " was not added");
    }

    private double hours(String userId, String from, String to) throws Exception {
        return availability(userId, from, to).path("workingHours").asDouble();
    }

    private JsonNode availability(String userId, String from, String to) throws Exception {
        return data(callAs(DEMO_OWNER, get(workers() + "/" + userId + "/availability").param("from", from).param("to", to), null));
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
