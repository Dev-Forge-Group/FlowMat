package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Every save and removal of a schedule version is kept (docs/architecture/adr/ADR-024-version-save-history.md). */
@AutoConfigureMockMvc
class ScheduleVersionHistoryIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void everySaveAndRemovalOfAWorkersVersionStaysInItsHistory() throws Exception {
        String kim = member("editor");
        String shifts = "/projects/" + DEMO_PROJECT + "/workers/" + kim + "/shifts";
        data(call(DEMO_OWNER, put(shifts), Map.of("shifts", List.of(shift("06:00", "14:00", 1, 2, 3, 4, 5)), "reason", "Day shift")));
        // Saving today's version again changes it; the history keeps both saves (WC63).
        data(call(DEMO_OWNER, put(shifts), Map.of("shifts", List.of(shift("07:00", "15:00", 1, 2, 3, 4, 5)), "reason", "Later start")));
        // 2041-09-09 is a Monday: a two-week rota from then.
        JsonNode calendar = data(call(DEMO_OWNER, put(shifts), Map.of("effectiveFrom", "2041-09-09", "reason", "Nights",
            "rota", Map.of("weeks", 2, "startsOn", "2041-09-09"),
            "shifts", List.of(Map.of("shiftStart", "22:00", "shiftEnd", "06:00", "workDays", List.of(1, 2, 3), "rotaWeek", 2)))));
        String future = null;
        for (JsonNode line : calendar.path("schedules")) {
            if ("2041-09-09".equals(line.path("effectiveFrom").asText())) future = line.path("scheduleId").asText();
        }
        data(call(DEMO_OWNER, delete("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/schedules/" + future), null));

        JsonNode history = data(call(DEMO_OWNER, get("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/schedule-history"), null));
        assertEquals(4, history.size(), history.toString());
        assertEquals("removed", history.get(0).path("action").asText());
        assertEquals(future, history.get(0).path("scheduleId").asText());
        assertEquals("2041-09-09", history.get(0).path("effectiveFrom").asText());
        assertEquals("Nights", history.get(0).path("reason").asText());
        assertEquals("22:00", history.get(0).path("shifts").get(0).path("shiftStart").asText());
        assertEquals(2, history.get(0).path("shifts").get(0).path("rotaWeek").asInt());
        assertEquals(2, history.get(0).path("rota").path("weeks").asInt());
        assertEquals("saved", history.get(1).path("action").asText());
        assertEquals("2041-09-09", history.get(1).path("effectiveFrom").asText());
        assertEquals("Later start", history.get(2).path("reason").asText());
        assertEquals("07:00", history.get(2).path("shifts").get(0).path("shiftStart").asText());
        assertEquals("[1,2,3,4,5]", history.get(2).path("shifts").get(0).path("workDays").toString());
        assertTrue(history.get(2).path("rota").isNull() || history.get(2).path("rota").isMissingNode());
        assertEquals("Day shift", history.get(3).path("reason").asText());
        assertEquals("06:00", history.get(3).path("shifts").get(0).path("shiftStart").asText());
        assertEquals(DEMO_OWNER, history.get(3).path("savedBy").asText());
        assertEquals(history.get(2).path("scheduleId").asText(), history.get(3).path("scheduleId").asText());

        // Readers of the project read it; an outsider does not.
        String viewer = member("viewer");
        call(viewer, get("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/schedule-history"), null).andExpect(status().isOk());
        call("unrelated-user", get("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/schedule-history"), null)
            .andExpect(status().isForbidden());
    }

    @Test
    void everySaveAndRemovalOfAnEquipmentCalendarVersionStaysInItsHistory() throws Exception {
        String press = data(call(DEMO_OWNER, post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentName",
            "History " + UUID.randomUUID(), "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))).path("equipmentId").asText();
        String calendar = "/equipments/" + press + "/calendar";
        data(call(DEMO_OWNER, put(calendar), Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5),
            "reason", "Day")));
        data(call(DEMO_OWNER, put(calendar), Map.of("effectiveFrom", "2041-09-09", "reason", "Two shifts", "shifts", List.of(
            Map.of("shiftStart", "06:00", "shiftEnd", "14:00", "workDays", List.of(1, 2, 3, 4, 5)),
            Map.of("shiftStart", "14:00", "shiftEnd", "22:00", "workDays", List.of(1, 2, 3, 4, 5))))));
        JsonNode schedule = data(call(DEMO_OWNER, delete(calendar).param("effectiveFrom", "2041-09-16").param("reason", "Overhaul"), null));
        String twoShifts = null;
        for (JsonNode line : schedule.path("versions")) {
            if ("2041-09-09".equals(line.path("effectiveFrom").asText())) twoShifts = line.path("scheduleId").asText();
        }
        data(call(DEMO_OWNER, delete(calendar + "/versions/" + twoShifts), null));

        JsonNode history = data(call(DEMO_OWNER, get(calendar + "/history"), null));
        assertEquals(4, history.size(), history.toString());
        assertEquals("removed", history.get(0).path("action").asText());
        assertEquals("2041-09-09", history.get(0).path("effectiveFrom").asText());
        assertEquals(2, history.get(0).path("shifts").size());
        // Removing the calendar from a date is a save with no shifts: around the clock from then (ADR-014).
        assertEquals("saved", history.get(1).path("action").asText());
        assertEquals("2041-09-16", history.get(1).path("effectiveFrom").asText());
        assertEquals(0, history.get(1).path("shifts").size());
        assertEquals("Overhaul", history.get(1).path("reason").asText());
        assertEquals("Two shifts", history.get(2).path("reason").asText());
        assertEquals("14:00", history.get(2).path("shifts").get(1).path("shiftStart").asText());
        assertEquals("Day", history.get(3).path("reason").asText());
        assertEquals("[1,2,3,4,5]", history.get(3).path("shifts").get(0).path("workDays").toString());
        call("unrelated-user", get(calendar + "/history"), null).andExpect(status().isForbidden());
    }

    private static Map<String, Object> shift(String start, String end, Integer... days) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", List.of(days));
    }

    /** A new member of the demo project. */
    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }

    private JsonNode data(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
