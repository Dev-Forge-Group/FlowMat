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

/** One worker's weekly shifts and rota given to a team, each some weeks further on (docs/domain/worker-calendar.md WC51-WC54). */
@AutoConfigureMockMvc
class WorkerTeamRotaIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aRotaGoesToATeamWithEachMemberSomeWeeksFurtherOn() throws Exception {
        String kim = member("editor");
        String lee = member("editor");
        String park = member("editor");
        String fresh = member("editor");
        // Kim: two weeks from 2041-09-02, early shifts then late ones.
        Map<String, Object> body = new HashMap<>();
        body.put("shifts", List.of(shift("06:00", "14:00", 1), shift("14:00", "22:00", 2)));
        body.put("rota", Map.of("weeks", 2, "startsOn", "2041-09-02"));
        callAs(DEMO_OWNER, put(workers() + "/" + kim + "/shifts"), json(body)).andExpect(status().isOk());
        // Lee had nights and a Tuesday off of their own; the nights go, the Tuesday stays.
        callAs(DEMO_OWNER, put(workers() + "/" + lee + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", "22:00", "shiftEnd", "06:00", "workDays", List.of(1, 2, 3, 4, 5)))))).andExpect(status().isOk());
        Map<String, Object> off = new HashMap<>();
        off.put("shifts", List.of());
        callAs(DEMO_OWNER, put(workers() + "/" + lee + "/days/2041-09-03"), json(off)).andExpect(status().isOk());

        JsonNode copied = data(copy(DEMO_OWNER, kim, List.of(target(lee, 0), target(park, 1))));
        assertEquals(2, copied.size());
        assertEquals(lee, copied.get(0).path("userId").asText());
        assertEquals("2041-09-02", copied.get(0).path("rota").path("startsOn").asText());
        assertEquals("2041-08-26", copied.get(1).path("rota").path("startsOn").asText());
        assertEquals(2, copied.get(1).path("rota").path("weeks").asInt());
        List<Integer> weeks = new ArrayList<>();
        copied.get(1).path("shifts").forEach(one -> weeks.add(one.path("rotaWeek").asInt()));
        assertEquals(List.of(1, 2), weeks);
        assertTrue(texts(copied.get(0).path("days"), "date").contains("2041-09-03"));

        // Monday 2041-09-02 is Kim's week 1: Kim and Lee work early, Park (a week on) works late.
        assertEquals(8.0, hours(kim, "2041-09-02T06:00:00+09:00", "2041-09-02T14:00:00+09:00"));
        assertEquals(8.0, hours(lee, "2041-09-02T06:00:00+09:00", "2041-09-02T14:00:00+09:00"));
        assertEquals(0.0, hours(park, "2041-09-02T06:00:00+09:00", "2041-09-02T14:00:00+09:00"));
        assertEquals(8.0, hours(park, "2041-09-02T14:00:00+09:00", "2041-09-02T22:00:00+09:00"));
        // Lee's own Tuesday off still holds over the copied early shift; the week after, Lee works late with Kim.
        assertEquals(0.0, hours(lee, "2041-09-03T06:00:00+09:00", "2041-09-03T14:00:00+09:00"));
        assertEquals(8.0, hours(lee, "2041-09-09T14:00:00+09:00", "2041-09-09T22:00:00+09:00"));

        // Refusals, all before anything is changed.
        copy(DEMO_OWNER, kim, List.of(target(lee, 0), target(park, 2))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("offsetWeeks is 0 to 1."));
        copy(DEMO_OWNER, kim, List.of(target(kim, 0))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose other workers to copy to."));
        copy(DEMO_OWNER, kim, List.of(target(lee, 0), target(lee, 1))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(lee + " is chosen twice."));
        String nobody = "nobody-" + UUID.randomUUID();
        copy(DEMO_OWNER, kim, List.of(target(nobody, 0))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(nobody + " is not a worker of this project."));
        copy(DEMO_OWNER, kim, List.of()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose 1 to 50 workers to copy to."));
        copy(DEMO_OWNER, fresh, List.of(target(lee, 0))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(fresh + " has no weekly shifts to copy."));
        callAs(DEMO_OWNER, post(workers() + "/" + kim + "/shifts/copy"),
                "{\"targets\":[{\"userId\":\"" + lee + "\",\"offsetWeeks\":0.5}]}")
            .andExpect(status().isBadRequest());
        // A worker may take someone's shifts for their own calendar, not give them to another's.
        copy(lee, kim, List.of(target(park, 0))).andExpect(status().isForbidden());
        assertEquals("2041-08-26", data(callAs(DEMO_OWNER, get(workers() + "/" + park), null)).path("rota").path("startsOn").asText());
        copy(lee, kim, List.of(target(lee, 1))).andExpect(status().isOk());
        assertEquals("2041-08-26", data(callAs(DEMO_OWNER, get(workers() + "/" + lee), null)).path("rota").path("startsOn").asText());

        // Shifts that are the same every week have no weeks to be further on in.
        callAs(DEMO_OWNER, put(workers() + "/" + fresh + "/shifts"), json(Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5)))))).andExpect(status().isOk());
        copy(DEMO_OWNER, fresh, List.of(target(park, 1))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("offsetWeeks needs a rota; " + fresh + " works the same every week."));
        JsonNode weekly = data(copy(DEMO_OWNER, fresh, List.of(target(park, 0)))).get(0);
        assertTrue(weekly.path("rota").isNull());
        assertEquals(40.0, weekly.path("weeklyHours").asDouble());
    }

    private ResultActions copy(String actor, String from, List<Map<String, Object>> targets) throws Exception {
        return callAs(actor, post(workers() + "/" + from + "/shifts/copy"), json(Map.of("targets", targets)));
    }

    private static Map<String, Object> target(String userId, int offsetWeeks) {
        return Map.of("userId", userId, "offsetWeeks", offsetWeeks);
    }

    private static Map<String, Object> shift(String start, String end, int week) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(1, 2, 3, 4, 5), "rotaWeek", week);
    }

    private double hours(String userId, String from, String to) throws Exception {
        return data(callAs(DEMO_OWNER, get(workers() + "/" + userId + "/availability").param("from", from).param("to", to), null))
            .path("workingHours").asDouble();
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> found = new ArrayList<>();
        array.forEach(one -> found.add(one.path(field).asText()));
        return found;
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
