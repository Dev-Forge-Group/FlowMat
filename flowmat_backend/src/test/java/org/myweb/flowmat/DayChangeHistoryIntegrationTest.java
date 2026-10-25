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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

/**
 * Every change of a worker's or equipment's own dates, days off and downtimes is kept
 * (docs/architecture/adr/ADR-024-version-save-history.md decisions 7-12).
 */
@AutoConfigureMockMvc
class DayChangeHistoryIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void everyChangeOfAWorkersDatesAndDaysOffStaysInTheirHistory() throws Exception {
        String kim = member("editor");
        String lee = member("editor");
        String base = "/projects/" + DEMO_PROJECT + "/workers/";
        for (String worker : List.of(kim, lee)) {
            data(call(DEMO_OWNER, put(base + worker + "/shifts"), Map.of("shifts", List.of(Map.of("shiftStart", "09:00",
                "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))))));
        }
        // 2041-09-02 is a Monday: overtime on the Monday, Wednesday and Friday of that week (WC31).
        data(call(DEMO_OWNER, put(base + kim + "/days/2041-09-02"), Map.of("shifts", List.of(Map.of("shiftStart", "06:00",
            "shiftEnd", "14:00")), "reason", "Overtime", "throughDate", "2041-09-06", "weekDays", List.of(1, 3, 5))));
        // The Wednesday again, now without work, by the worker.
        data(call(kim, put(base + kim + "/days/2041-09-04"), Map.of("shifts", List.of(), "reason", "No work")));
        data(call(DEMO_OWNER, delete(base + kim + "/days/2041-09-02").param("through", "2041-09-04"), null));
        data(call(DEMO_OWNER, post(base + kim + "/days/copy"), Map.of("toUserId", lee)));
        JsonNode calendar = data(call(DEMO_OWNER, post(base + kim + "/days-off"), Map.of("firstDay", "2041-10-01",
            "lastDay", "2041-10-03", "reason", "Holiday")));
        String dayOff = null;
        for (JsonNode off : calendar.path("daysOff")) {
            if ("2041-10-01".equals(off.path("firstDay").asText())) dayOff = off.path("dayOffId").asText();
        }
        data(call(DEMO_OWNER, delete(base + kim + "/days-off/" + dayOff), null));
        // A refused change leaves no line.
        call(DEMO_OWNER, put(base + kim + "/days/2041-09-09"), Map.of("shifts", List.of(Map.of("shiftStart", "25:00",
            "shiftEnd", "14:00")))).andExpect(status().isBadRequest());

        JsonNode history = data(call(DEMO_OWNER, get(base + kim + "/day-history"), null));
        assertEquals(5, history.size(), history.toString());
        assertLine(history.get(0), "day_off", "removed", "2041-10-01", "2041-10-03");
        assertEquals(dayOff, history.get(0).path("dayOffId").asText());
        assertEquals("Holiday", history.get(0).path("reason").asText());
        assertEquals(0, history.get(0).path("days").size());
        assertLine(history.get(1), "day_off", "saved", "2041-10-01", "2041-10-03");
        assertEquals(DEMO_OWNER, history.get(1).path("savedBy").asText());
        // Removing dates keeps what each had then.
        assertLine(history.get(2), "day", "removed", "2041-09-02", "2041-09-04");
        assertEquals(List.of("2041-09-02", "2041-09-04"), dates(history.get(2)));
        assertEquals("06:00", history.get(2).path("days").get(0).path("shifts").get(0).path("shiftStart").asText());
        assertEquals("Overtime", history.get(2).path("days").get(0).path("reason").asText());
        assertEquals(0, history.get(2).path("days").get(1).path("shifts").size());
        assertEquals("No work", history.get(2).path("days").get(1).path("reason").asText());
        assertTrue(absent(history.get(2).path("reason")));
        assertLine(history.get(3), "day", "saved", "2041-09-04", "2041-09-04");
        assertEquals(kim, history.get(3).path("savedBy").asText());
        assertEquals("No work", history.get(3).path("reason").asText());
        assertLine(history.get(4), "day", "saved", "2041-09-02", "2041-09-06");
        assertEquals(List.of("2041-09-02", "2041-09-04", "2041-09-06"), dates(history.get(4)));
        assertEquals("14:00", history.get(4).path("days").get(2).path("shifts").get(0).path("shiftEnd").asText());
        assertEquals(DEMO_OWNER, history.get(4).path("savedBy").asText());
        assertTrue(absent(history.get(4).path("copiedFrom")));

        // The copy is a save on the worker it went to, naming where it came from.
        JsonNode copied = data(call(DEMO_OWNER, get(base + lee + "/day-history"), null));
        assertEquals(1, copied.size(), copied.toString());
        assertLine(copied.get(0), "day", "saved", "2041-09-06", "2041-09-06");
        assertEquals(kim, copied.get(0).path("copiedFrom").asText());
        assertEquals("Overtime", copied.get(0).path("days").get(0).path("reason").asText());

        // Readers of the project read it; an outsider does not.
        call(member("viewer"), get(base + kim + "/day-history"), null).andExpect(status().isOk());
        call("unrelated-user", get(base + kim + "/day-history"), null).andExpect(status().isForbidden());
    }

    @Test
    void everyChangeOfAnEquipmentsDatesAndDowntimesStaysInItsHistory() throws Exception {
        String press = equipment("Press");
        String drill = equipment("Drill");
        for (String one : List.of(press, drill)) {
            data(call(DEMO_OWNER, put("/equipments/" + one + "/calendar"), Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
                "workDays", List.of(1, 2, 3, 4, 5))));
        }
        String base = "/equipments/" + press;
        data(call(DEMO_OWNER, put(base + "/days/2041-09-02"), Map.of("shifts", List.of(Map.of("shiftStart", "06:00",
            "shiftEnd", "10:00")), "reason", "Short day")));
        data(call(DEMO_OWNER, delete(base + "/days/2041-09-02"), null));
        data(call(DEMO_OWNER, put(base + "/days/2041-09-03"), Map.of("shifts", List.of(), "reason", "Closed")));
        data(call(DEMO_OWNER, post(base + "/days/copy"), Map.of("toEquipmentId", drill)));
        JsonNode schedule = data(call(DEMO_OWNER, post(base + "/downtimes"), Map.of("downtimeType", "maintenance",
            "startsAt", "2041-09-05T08:00:00Z", "endsAt", "2041-09-05T12:00:00Z", "reason", "Oil change")));
        String downtime = schedule.path("downtimes").get(0).path("downtimeId").asText();
        data(call(DEMO_OWNER, delete(base + "/downtimes/" + downtime), null));

        JsonNode history = data(call(DEMO_OWNER, get(base + "/day-history"), null));
        assertEquals(5, history.size(), history.toString());
        assertEquals("downtime", history.get(0).path("kind").asText());
        assertEquals("removed", history.get(0).path("action").asText());
        assertEquals(downtime, history.get(0).path("downtimeId").asText());
        assertEquals("maintenance", history.get(0).path("downtimeType").asText());
        assertEquals(Instant.parse("2041-09-05T08:00:00Z"), OffsetDateTime.parse(history.get(0).path("startsAt").asText()).toInstant());
        assertEquals(Instant.parse("2041-09-05T12:00:00Z"), OffsetDateTime.parse(history.get(0).path("endsAt").asText()).toInstant());
        assertEquals("Oil change", history.get(0).path("reason").asText());
        assertTrue(absent(history.get(0).path("firstDate")));
        assertEquals(0, history.get(0).path("days").size());
        assertEquals("downtime", history.get(1).path("kind").asText());
        assertEquals("saved", history.get(1).path("action").asText());
        assertLine(history.get(2), "day", "saved", "2041-09-03", "2041-09-03");
        assertEquals(0, history.get(2).path("days").get(0).path("shifts").size());
        assertEquals("Closed", history.get(2).path("reason").asText());
        assertLine(history.get(3), "day", "removed", "2041-09-02", "2041-09-02");
        assertEquals("10:00", history.get(3).path("days").get(0).path("shifts").get(0).path("shiftEnd").asText());
        assertEquals("Short day", history.get(3).path("days").get(0).path("reason").asText());
        assertLine(history.get(4), "day", "saved", "2041-09-02", "2041-09-02");
        assertEquals("Short day", history.get(4).path("reason").asText());

        JsonNode copied = data(call(DEMO_OWNER, get("/equipments/" + drill + "/day-history"), null));
        assertEquals(1, copied.size(), copied.toString());
        assertLine(copied.get(0), "day", "saved", "2041-09-03", "2041-09-03");
        assertEquals(press, copied.get(0).path("copiedFrom").asText());
        assertEquals("Closed", copied.get(0).path("days").get(0).path("reason").asText());
        call("unrelated-user", get(base + "/day-history"), null).andExpect(status().isForbidden());
    }

    private static void assertLine(JsonNode line, String kind, String action, String first, String last) {
        assertEquals(kind, line.path("kind").asText(), line.toString());
        assertEquals(action, line.path("action").asText(), line.toString());
        assertEquals(first, line.path("firstDate").asText(), line.toString());
        assertEquals(last, line.path("lastDate").asText(), line.toString());
    }

    private static List<String> dates(JsonNode line) {
        List<String> dates = new ArrayList<>();
        line.path("days").forEach(day -> dates.add(day.path("date").asText()));
        return dates;
    }

    private static boolean absent(JsonNode value) {
        return value.isNull() || value.isMissingNode();
    }

    private String equipment(String name) throws Exception {
        return data(call(DEMO_OWNER, post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentName",
            name + " " + UUID.randomUUID(), "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))).path("equipmentId").asText();
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
