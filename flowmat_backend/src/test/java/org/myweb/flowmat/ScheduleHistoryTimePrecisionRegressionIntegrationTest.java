package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A saved history snapshot must describe the shift that another transaction actually reads from Postgres. */
@AutoConfigureMockMvc
class ScheduleHistoryTimePrecisionRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    static Stream<Arguments> times() {
        return Stream.of("worker", "equipment").flatMap(kind -> Stream.of("07:00:00.123456789", "07:00:00.000000001",
            "07:00:59.999999999", "23:59:59.999999999").map(start -> Arguments.of(kind, start)));
    }

    @ParameterizedTest
    @MethodSource("times")
    void savedHistoryMatchesThePersistedShiftTime(String kind, String start) throws Exception {
        String project = data(post("/projects"), Map.of("projectName", "History precision " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        String base;
        String historyPath;
        if ("worker".equals(kind)) {
            base = "/projects/" + project + "/workers/" + DEMO_OWNER;
            historyPath = base + "/schedule-history";
            data(put(base + "/shifts"), Map.of("shifts", List.of(shift(start)), "reason", "Precision check"));
        } else {
            String equipment = data(post("/equipments"), Map.of("projectId", project, "equipmentName", "Precision press",
                "equipmentType", "machine", "details", Map.of("capacityPerHour", 10))).path("equipmentId").asText();
            base = "/equipments/" + equipment;
            historyPath = base + "/calendar/history";
            data(put(base + "/calendar"), Map.of("shifts", List.of(shift(start)), "reason", "Precision check"));
        }
        JsonNode history = data(get(historyPath), null);
        assertEquals(1, history.size());
        String schedule = history.get(0).path("scheduleId").asText();
        String versionPath = base + ("worker".equals(kind) ? "/schedules/" : "/calendar/versions/") + schedule;
        data(get(versionPath), null);
        String table = "worker".equals(kind) ? "worker_shift" : "equipment_calendar";
        LocalTime[] stored = jdbc.queryForObject("select shift_start,shift_end from " + table + " where schedule_id=?",
            (row, index) -> new LocalTime[] { row.getObject(1, LocalTime.class), row.getObject(2, LocalTime.class) }, schedule);
        JsonNode snapshot = history.get(0).path("shifts").get(0);
        assertEquals(stored[0], LocalTime.parse(snapshot.path("shiftStart").asText()),
            "History must retain the time stored by the successful save");
        assertEquals(stored[1], LocalTime.parse(snapshot.path("shiftEnd").asText()));
    }

    private static Map<String, Object> shift(String start) {
        return Map.of("shiftStart", start, "shiftEnd", "16:00:00.987654321", "workDays", List.of(1, 2, 3, 4, 5));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
