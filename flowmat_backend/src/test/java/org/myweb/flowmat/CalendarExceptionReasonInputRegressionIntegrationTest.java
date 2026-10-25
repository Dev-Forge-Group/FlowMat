package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Calendar exceptions must reject unstoreable raw text before trimming or JDBC can discard or replace it. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CalendarExceptionReasonInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    static Stream<Arguments> invalidReasons() {
        return Stream.of("worker-day", "day-off", "equipment-day", "downtime").flatMap(route -> Stream.of(
            "bad\\u0000reason", "\\u0000reason", "bad\\ud800reason", "bad\\udc00reason")
            .map(value -> Arguments.of(route, value)));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidReasons")
    void invalidRawCharactersReturn400WithoutChangingTheCalendar(String route, String escaped) throws Exception {
        Fixture f = new Fixture(route);
        var before = f.state();
        f.send("\"" + escaped + "\"").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", Matchers.containsString("reason")));
        assertEquals(before, f.state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-day", "day-off", "equipment-day", "downtime"})
    void validUnicodeKeepsItsCharactersAndOnlyOuterWhitespaceIsTrimmed(String route) throws Exception {
        Fixture f = new Fixture(route);
        String text = "  Changed \ud55c\uae00 \ud83d\udd25  ";
        f.send(mapper.writeValueAsString(text)).andExpect(status().isOk());
        assertTrue(f.reasons().contains(text.trim()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-day", "day-off", "equipment-day", "downtime"})
    void theExistingMaximumLengthIsAcceptedAndOneMoreCharacterLeavesRowsAlone(String route) throws Exception {
        Fixture f = new Fixture(route);
        int maximum = "downtime".equals(route) ? 500 : 200;
        String text = "x".repeat(maximum);
        f.send(mapper.writeValueAsString(text)).andExpect(status().isOk());
        assertTrue(f.reasons().contains(text));
        var before = f.state();
        f.send(mapper.writeValueAsString(text + "x")).andExpect(status().isBadRequest());
        assertEquals(before, f.state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-day", "day-off", "equipment-day", "downtime"})
    void nullAndWhitespaceContinueToMeanNoReason(String route) throws Exception {
        Fixture f = new Fixture(route);
        f.send(mapper.writeValueAsString(" \t ")).andExpect(status().isOk());
        assertTrue(f.reasons().contains(null));
        f.send("null").andExpect(status().isOk());
        assertTrue(f.reasons().contains(null));
    }

    private final class Fixture {
        final String route;
        final String project;
        final String equipment;
        final String table;
        final String parentColumn;
        final String parentId;

        Fixture(String route) throws Exception {
            this.route = route;
            project = data(post("/projects"), Map.of("projectName", "Calendar reason " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
                .path("projectId").asText();
            equipment = route.startsWith("equipment") || "downtime".equals(route)
                ? data(post("/equipments"), Map.of("projectId", project, "equipmentName", "Reason press", "equipmentType", "machine"))
                    .path("equipmentId").asText() : null;
            table = switch (route) {
                case "worker-day" -> "worker_day_shift";
                case "day-off" -> "worker_day_off";
                case "equipment-day" -> "equipment_day_override";
                case "downtime" -> "equipment_downtime";
                default -> throw new IllegalArgumentException(route);
            };
            parentColumn = equipment == null ? "project_id" : "equipment_id";
            parentId = equipment == null ? project : equipment;
            if (route.endsWith("day")) {
                String weekly = equipment == null ? "/projects/" + project + "/workers/" + DEMO_OWNER + "/shifts"
                    : "/equipments/" + equipment + "/calendar";
                data(put(weekly), Map.of("shifts", List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00",
                    "workDays", List.of(1, 2, 3, 4, 5)))));
            }
            send("\"Existing\"").andExpect(status().isOk());
        }

        ResultActions send(String reasonJson) throws Exception {
            String worker = "/projects/" + project + "/workers/" + DEMO_OWNER;
            String path = switch (route) {
                case "worker-day" -> worker + "/days/2041-09-09";
                case "day-off" -> worker + "/days-off";
                case "equipment-day" -> "/equipments/" + equipment + "/days/2041-09-09";
                case "downtime" -> "/equipments/" + equipment + "/downtimes";
                default -> throw new IllegalArgumentException(route);
            };
            String fields = switch (route) {
                case "worker-day", "equipment-day" -> "\"shifts\":[{\"shiftStart\":\"09:00\",\"shiftEnd\":\"17:00\"}],"
                    + "\"throughDate\":\"2041-09-10\",";
                case "day-off" -> "\"firstDay\":\"2041-09-09\",\"lastDay\":\"2041-09-10\",";
                case "downtime" -> "\"startsAt\":\"2041-09-09T09:00:00Z\",\"endsAt\":\"2041-09-09T17:00:00Z\",";
                default -> throw new IllegalArgumentException(route);
            };
            MockHttpServletRequestBuilder request = route.endsWith("day") ? put(path) : post(path);
            return mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{" + fields + "\"reason\":" + reasonJson + "}"));
        }

        List<Map<String, Object>> state() {
            return jdbc.queryForList("select * from " + table + " where " + parentColumn + "=? order by 1", parentId);
        }

        List<String> reasons() {
            return jdbc.queryForList("select reason from " + table + " where " + parentColumn + "=?", String.class, parentId);
        }
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
