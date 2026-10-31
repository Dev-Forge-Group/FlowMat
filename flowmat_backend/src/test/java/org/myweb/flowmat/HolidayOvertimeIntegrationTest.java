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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Holiday overtime in the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 18-19), on real Postgres. */
@AutoConfigureMockMvc
class HolidayOvertimeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Holiday overtime " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        JsonNode zone = data(get("/projects/" + projectId + "/time-zone"), null);
        data(put("/projects/" + projectId + "/time-zone"), Map.of("timeZone", "Asia/Seoul", "expectedVersion", zone.path("version").asLong()));
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @Test
    void holidayMinutesPastTheStartGetTheHolidayOvertimeInPlaceOfTheDaily() throws Exception {
        String kim = member();
        String rules = "/projects/" + projectId + "/labor-premiums";
        // Half of it, odd minutes and percents, and holiday overtime without a holiday percent are refused.
        for (Map<String, Object> wrong : List.<Map<String, Object>>of(Map.of("holidayPercent", 50, "holidayOvertimeAfterMinutes", 480),
                Map.of("holidayOvertimeAfterMinutes", 480, "holidayOvertimePercent", 100),
                Map.of("holidayPercent", 50, "holidayOvertimeAfterMinutes", 0, "holidayOvertimePercent", 100),
                Map.of("holidayPercent", 50, "holidayOvertimeAfterMinutes", 1441, "holidayOvertimePercent", 100),
                Map.of("holidayPercent", 50, "holidayOvertimeAfterMinutes", 480, "holidayOvertimePercent", 501))) {
            call(put(rules), wrong).andExpect(status().isBadRequest());
        }
        // kim works Monday to Friday 9 to 17 from the start; Thursday 2026-09-03 is a project holiday.
        data(put("/projects/" + projectId + "/workers/" + kim + "/shifts"), Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5)))));
        jdbc.update("update worker_schedule set effective_from = null where project_id = ? and user_id = ?", projectId, kim);
        data(put("/projects/" + projectId + "/workers/" + kim + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        data(post("/holidays"), Map.of("projectId", projectId, "date", "2026-09-03", "name", "Test holiday"));
        // 10 h on Wednesday, on the holiday Thursday and on the rest day Saturday.
        for (String date : List.of("2026-09-02", "2026-09-03", "2026-09-05")) {
            attend(kim, date + "T09:00:00+09:00", date + "T19:00:00+09:00");
        }
        // Overtime after 8 h 50% more; holidays and rest days 50% more, and past 8 h of them 100% more again.
        JsonNode saved = data(put(rules), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50, "holidayPercent", 50,
            "restDaysAsHolidays", true, "holidayOvertimeAfterMinutes", 480, "holidayOvertimePercent", 100)).path("current");
        assertEquals(480, saved.path("holidayOvertimeAfterMinutes").asInt());
        assertEquals(100, saved.path("holidayOvertimePercent").asInt());
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);

        JsonNode pay = data(get("/projects/" + projectId + "/workers/" + kim + "/attendance-pay").param("from", "2026-09-02")
            .param("to", "2026-09-05"), null);
        // Wednesday: 2 h of daily overtime at 50%.
        JsonNode wednesday = pay.path("days").get(0);
        assertEquals(120, wednesday.path("overtimeMinutes").asInt(), pay.toString());
        assertEquals(0, wednesday.path("holidayOvertimeMinutes").asInt());
        assertMoney("60", wednesday.path("premiumMinutes"));
        // The holiday: 10 h at 50% more and the 2 h past 8 at 100% more again, no daily overtime.
        JsonNode thursday = pay.path("days").get(1);
        assertTrue(thursday.path("holiday").asBoolean());
        assertEquals(0, thursday.path("overtimeMinutes").asInt());
        assertEquals(120, thursday.path("holidayOvertimeMinutes").asInt());
        assertMoney("420", thursday.path("premiumMinutes"));
        assertMoney("1020", thursday.path("amount"));
        // The rest day paid like a holiday the same way.
        JsonNode saturday = pay.path("days").get(2);
        assertTrue(saturday.path("restDayHoliday").asBoolean());
        assertEquals(120, saturday.path("holidayOvertimeMinutes").asInt());
        assertMoney("420", saturday.path("premiumMinutes"));
        assertMoney("900", pay.path("premiumMinutes"));
        assertMoney("2700", pay.path("amount"));

        // Without holiday overtime the holiday's 2 h past 8 are daily overtime at 50%.
        data(put(rules), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50, "holidayPercent", 50));
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);
        JsonNode plain = data(get("/projects/" + projectId + "/workers/" + kim + "/attendance-pay").param("from", "2026-09-03")
            .param("to", "2026-09-03"), null).path("days").get(0);
        assertEquals(120, plain.path("overtimeMinutes").asInt());
        assertEquals(0, plain.path("holidayOvertimeMinutes").asInt());
        assertMoney("360", plain.path("premiumMinutes"));
    }

    private void attend(String userId, String clockIn, String clockOut) throws Exception {
        data(post("/projects/" + projectId + "/workers/" + userId + "/attendance"),
            Map.of("requestId", UUID.randomUUID().toString(), "clockIn", clockIn, "clockOut", clockOut));
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), actual.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, "editor");
        return id;
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
