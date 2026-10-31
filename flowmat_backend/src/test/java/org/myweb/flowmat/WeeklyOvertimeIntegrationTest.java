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

/** Weekly overtime in the pay premiums (docs/architecture/adr/ADR-028-pay-premiums.md decisions 13-15), on real Postgres. */
@AutoConfigureMockMvc
class WeeklyOvertimeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Weekly overtime " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void regularMinutesPastTheWeekStartGetTheWeeklyPremium() throws Exception {
        String kim = member();
        String rules = "/projects/" + projectId + "/labor-premiums";
        // Half of it, odd minutes and days, and a week start without weekly overtime are refused.
        for (Map<String, Object> wrong : List.<Map<String, Object>>of(Map.of("weeklyOvertimeAfterMinutes", 2400),
                Map.of("weeklyOvertimePercent", 50), Map.of("weeklyOvertimeAfterMinutes", 10081, "weeklyOvertimePercent", 50),
                Map.of("weeklyOvertimeAfterMinutes", 2400, "weeklyOvertimePercent", 50, "weekStartsOn", 8),
                Map.of("holidayPercent", 50, "weekStartsOn", 1))) {
            call(put(rules), wrong).andExpect(status().isBadRequest());
        }
        // Overtime after 8 h a day and 40 h a week, each 50% more; the week starts on Monday when not given.
        JsonNode saved = data(put(rules), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50, "weeklyOvertimeAfterMinutes", 2400,
            "weeklyOvertimePercent", 50)).path("current");
        assertEquals(2400, saved.path("weeklyOvertimeAfterMinutes").asInt());
        assertEquals(1, saved.path("weekStartsOn").asInt());
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);
        data(put("/projects/" + projectId + "/workers/" + kim + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        // Monday 2026-08-31 to Friday 9 h a day, Saturday 4 h.
        for (String date : List.of("2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04")) {
            attend(kim, date + "T08:00:00+09:00", date + "T17:00:00+09:00");
        }
        attend(kim, "2026-09-05T09:00:00+09:00", "2026-09-05T13:00:00+09:00");
        String pay = "/projects/" + projectId + "/workers/" + kim + "/attendance-pay";

        // Each weekday has 1 h of daily overtime and 8 h regular: 40 regular hours by Friday, so Saturday's 4 h are weekly overtime.
        JsonNode week = data(get(pay).param("from", "2026-08-31").param("to", "2026-09-06"), null);
        assertEquals(6, week.path("days").size(), week.toString());
        JsonNode friday = week.path("days").get(4);
        assertEquals(60, friday.path("overtimeMinutes").asInt());
        assertEquals(0, friday.path("weeklyOvertimeMinutes").asInt());
        JsonNode saturday = week.path("days").get(5);
        assertEquals("2026-09-05", saturday.path("workDate").asText());
        assertEquals(0, saturday.path("overtimeMinutes").asInt());
        assertEquals(240, saturday.path("weeklyOvertimeMinutes").asInt());
        assertMoney("120", saturday.path("premiumMinutes"));
        assertEquals(2940, week.path("attendedMinutes").asInt());
        assertMoney("270", week.path("premiumMinutes"));
        assertMoney("3210", week.path("amount"));

        // Asked from Friday, the days of the week before it still count.
        JsonNode fromFriday = data(get(pay).param("from", "2026-09-04").param("to", "2026-09-05"), null);
        assertEquals(2, fromFriday.path("days").size());
        assertEquals(240, fromFriday.path("days").get(1).path("weeklyOvertimeMinutes").asInt());

        // A week that starts on Saturday puts Saturday in a new week with 4 regular hours: no weekly overtime.
        data(put(rules), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50, "weeklyOvertimeAfterMinutes", 2400,
            "weeklyOvertimePercent", 50, "weekStartsOn", 6));
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);
        JsonNode bySaturday = data(get(pay).param("from", "2026-09-05").param("to", "2026-09-05"), null);
        assertEquals(0, bySaturday.path("days").get(0).path("weeklyOvertimeMinutes").asInt());
        assertMoney("0", bySaturday.path("premiumMinutes"));
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
