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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Versions of a worker's weekly shifts (docs/architecture/adr/ADR-013-rota-history.md, V93). */
@AutoConfigureMockMvc
class WorkerScheduleHistoryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkerCalendarQuery workerCalendars;

    @Test
    void shiftsApplyFromTheirStartDateAndEarlierDatesKeepTheirs() throws Exception {
        String lee = member("editor");
        Map<String, Object> early = shift("06:00", "14:00");
        JsonNode first = data(save(lee, List.of(early), null));
        assertEquals(1, first.path("schedules").size());
        assertTrue(first.path("schedules").get(0).path("current").asBoolean());
        String today = first.path("schedules").get(0).path("effectiveFrom").asText();

        // From Monday 2041-09-09 lee works 14-20; the week before keeps the early shifts, and today's version is still shown.
        JsonNode later = data(save(lee, List.of(shift("14:00", "20:00")), "2041-09-09"));
        assertEquals(2, later.path("schedules").size());
        assertEquals("06:00", later.path("shifts").get(0).path("shiftStart").asText());
        assertEquals(40.0, hours(lee, "2041-09-02"));
        assertEquals(30.0, hours(lee, "2041-09-09"));
        // Saving again from the same date changes that version.
        JsonNode again = data(save(lee, List.of(shift("14:00", "21:00")), "2041-09-09"));
        assertEquals(2, again.path("schedules").size());
        assertEquals(35.0, hours(lee, "2041-09-09"));
        JsonNode futureLine = again.path("schedules").get(1);
        assertEquals("2041-09-09", futureLine.path("effectiveFrom").asText());
        assertFalse(futureLine.path("current").asBoolean());
        String future = futureLine.path("scheduleId").asText();
        JsonNode version = data(callAs(DEMO_OWNER, get(workers() + "/" + lee + "/schedules/" + future), null));
        assertFalse(version.path("current").asBoolean());
        assertEquals(35.0, version.path("weeklyHours").asDouble());
        assertEquals("14:00", version.path("shifts").get(0).path("shiftStart").asText());
        // Plans read the same versions through the public API.
        assertEquals(0, new BigDecimal("35.00").compareTo(workerCalendars.workingHours(DEMO_PROJECT, List.of(lee),
            OffsetDateTime.parse("2041-09-09T00:00:00+09:00"), OffsetDateTime.parse("2041-09-16T00:00:00+09:00")).get(lee).hours()));

        // A start before today is refused; a past date changes through its own shifts.
        String yesterday = LocalDate.parse(today).minusDays(1).toString();
        save(lee, List.of(early), yesterday).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
            .value("effectiveFrom must be today (" + today + ") or later; change a past date with that date's own shifts."));
        // Before lee's first version there are no weekly shifts.
        OffsetDateTime twoWeeksAgo = LocalDate.parse(today).minusDays(14).atStartOfDay().atOffset(ZoneOffset.ofHours(9));
        JsonNode past = data(callAs(DEMO_OWNER, get(workers() + "/" + lee + "/availability").param("from", twoWeeksAgo.toString())
            .param("to", twoWeeksAgo.plusDays(7).toString()), null));
        assertFalse(past.path("calendarSet").asBoolean());
        assertEquals(0.0, past.path("workingHours").asDouble());

        // A version that has started stays; one that has not can go.
        String current = again.path("schedules").get(0).path("scheduleId").asText();
        callAs(DEMO_OWNER, delete(workers() + "/" + lee + "/schedules/" + current), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This schedule has started; save a new one from a later date instead."));
        JsonNode removed = data(callAs(DEMO_OWNER, delete(workers() + "/" + lee + "/schedules/" + future), null));
        assertEquals(1, removed.path("schedules").size());
        assertEquals(40.0, hours(lee, "2041-09-09"));
        callAs(DEMO_OWNER, get(workers() + "/" + lee + "/schedules/" + future), null).andExpect(status().isNotFound());

        // A team copy starts on its date too: kim has no weekly shifts before 2041-10-06.
        String kim = member("editor");
        Map<String, Object> copy = new HashMap<>();
        copy.put("targets", List.of(Map.of("userId", kim)));
        copy.put("effectiveFrom", "2041-10-06");
        callAs(DEMO_OWNER, post(workers() + "/" + lee + "/shifts/copy"), json(copy)).andExpect(status().isOk());
        assertEquals(0.0, hours(kim, "2041-09-29"));
        assertEquals(40.0, hours(kim, "2041-10-06"));
        JsonNode kimNow = data(callAs(DEMO_OWNER, get(workers() + "/" + kim), null));
        assertFalse(kimNow.path("calendarSet").asBoolean());
        assertEquals("2041-10-06", kimNow.path("schedules").get(0).path("effectiveFrom").asText());
    }

    private ResultActions save(String userId, List<Map<String, Object>> shifts, String effectiveFrom) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", shifts);
        if (effectiveFrom != null) {
            body.put("effectiveFrom", effectiveFrom);
        }
        return callAs(DEMO_OWNER, put(workers() + "/" + userId + "/shifts"), json(body));
    }

    /** Monday to Friday. */
    private static Map<String, Object> shift(String start, String end) {
        Map<String, Object> shift = new HashMap<>();
        shift.put("shiftStart", start);
        shift.put("shiftEnd", end);
        shift.put("workDays", List.of(1, 2, 3, 4, 5));
        return shift;
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
