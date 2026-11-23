package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** A worker's rotating rota (docs/domain/worker-calendar.md WC44-WC50). */
@AutoConfigureMockMvc
class WorkerRotaIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerCalendarQuery workerCalendars;

    @Test
    void weeklyShiftsRepeatEveryFewWeeksFromTheStartWeekBothWays() throws Exception {
        String lee = member("editor");
        // Two weeks from Wednesday 2041-09-04, so week 1 is the week of Monday 2041-09-02: early shifts, then nights with
        // a Sunday night that runs into the next week 1 Monday.
        Map<String, Object> early = shift("06:00", "14:00", List.of(1, 2, 3, 4, 5), 1);
        Map<String, Object> nights = shift("22:00", "06:00", List.of(1, 2, 3, 4), 2);
        Map<String, Object> sunday = shift("22:00", "06:00", List.of(7), 2);
        JsonNode saved = data(shifts(lee, List.of(early, nights, sunday), rota(2, "2041-09-04")));
        assertEquals(2, saved.path("rota").path("weeks").asInt());
        assertEquals("2041-09-02", saved.path("rota").path("startsOn").asText());
        int now = saved.path("rota").path("currentWeek").asInt();
        assertTrue(now == 1 || now == 2);
        assertEquals(40.0, saved.path("weeklyHours").asDouble());
        List<Integer> weeks = new ArrayList<>();
        saved.path("shifts").forEach(one -> weeks.add(one.path("rotaWeek").asInt()));
        assertEquals(List.of(1, 2, 2), weeks);

        // Week 1: 40 early hours and the 6 h the week before's Sunday night (a week 2, counting back) brings into Monday.
        assertEquals(46.0, hours(lee, "2041-09-02"));
        // Week 2: four nights and 2 h of the Sunday night; week 1 again; the week before the start is a week 2.
        assertEquals(34.0, hours(lee, "2041-09-09"));
        assertEquals(46.0, hours(lee, "2041-09-16"));
        assertEquals(34.0, hours(lee, "2041-08-26"));
        // Plans read the same through the public API.
        assertEquals(0, new BigDecimal("34.00").compareTo(workerCalendars.workingHours(DEMO_PROJECT, List.of(lee),
            OffsetDateTime.parse("2041-09-09T00:00:00+09:00"), OffsetDateTime.parse("2041-09-16T00:00:00+09:00")).get(lee).hours()));

        // A date's own shifts still win: Monday of week 2 off takes that night's 8 h.
        Map<String, Object> off = new HashMap<>();
        off.put("shifts", List.of());
        callAs(DEMO_OWNER, put(workers() + "/" + lee + "/days/2041-09-09"), json(off)).andExpect(status().isOk());
        assertEquals(26.0, hours(lee, "2041-09-09"));
        callAs(DEMO_OWNER, delete(workers() + "/" + lee + "/days/2041-09-09"), null).andExpect(status().isOk());

        // Overlaps are checked over the whole rota: the last Sunday night runs into the first Monday.
        shifts(lee, List.of(shift("05:00", "13:00", List.of(1), 1), sunday), rota(2, "2041-09-02"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Shifts 1 and 2 overlap in week 1 on Mon."));
        shifts(lee, List.of(early), rota(1, "2041-09-02")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A rota runs 2 to 8 weeks."));
        shifts(lee, List.of(early), rota(9, "2041-09-02")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A rota runs 2 to 8 weeks."));
        shifts(lee, List.of(shift("06:00", "14:00", List.of(1), 3)), rota(2, "2041-09-02")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("rotaWeek is 1 to 2."));
        shifts(lee, List.of(shift("06:00", "14:00", List.of(1), 2)), null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("rotaWeek needs a rota; send rota with weeks and startsOn."));
        shifts(lee, List.of(early), rota(2, "next week")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("startsOn must be a date such as 2030-01-09."));
        shifts(lee, List.of(early), rota(2, null)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("startsOn is required."));
        List<Map<String, Object>> seven = new ArrayList<>();
        for (int hour = 0; hour < 14; hour += 2) {
            seven.add(shift(String.format("%02d:00", hour), String.format("%02d:00", hour + 1), List.of(1), 2));
        }
        shifts(lee, seven, rota(2, "2041-09-02")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A rota week can have at most 6 shifts."));
        callAs(DEMO_OWNER, put(workers() + "/" + lee + "/shifts"),
                "{\"shifts\":[],\"rota\":{\"weeks\":2.5,\"startsOn\":\"2041-09-02\"}}")
            .andExpect(status().isBadRequest());
        // The refusals left the rota as it was.
        assertEquals(34.0, hours(lee, "2041-09-09"));

        // Without a rota the shifts are one week again: the early shifts every week.
        Map<String, Object> plain = new HashMap<>(early);
        plain.remove("rotaWeek");
        JsonNode single = data(shifts(lee, List.of(plain), null));
        assertTrue(single.path("rota").isNull());
        assertEquals(40.0, single.path("weeklyHours").asDouble());
        assertEquals(1, single.path("shifts").get(0).path("rotaWeek").asInt());
        assertEquals(40.0, hours(lee, "2041-09-09"));
    }

    private ResultActions shifts(String userId, List<Map<String, Object>> shifts, Map<String, Object> rota) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", shifts);
        body.put("rota", rota);
        return callAs(DEMO_OWNER, put(workers() + "/" + userId + "/shifts"), json(body));
    }

    private static Map<String, Object> shift(String start, String end, List<Integer> days, int week) {
        Map<String, Object> shift = new HashMap<>();
        shift.put("shiftStart", start);
        shift.put("shiftEnd", end);
        shift.put("workDays", days);
        shift.put("rotaWeek", week);
        return shift;
    }

    private static Map<String, Object> rota(int weeks, String startsOn) {
        Map<String, Object> rota = new HashMap<>();
        rota.put("weeks", weeks);
        rota.put("startsOn", startsOn);
        return rota;
    }

    /** Working hours in the week from that Monday. */
    private double hours(String userId, String monday) throws Exception {
        OffsetDateTime from = OffsetDateTime.parse(monday + "T00:00:00+09:00");
        return data(callAs(DEMO_OWNER, get(workers() + "/" + userId + "/availability").param("from", from.toString())
            .param("to", from.plusDays(7).toString()), null)).path("workingHours").asDouble();
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
