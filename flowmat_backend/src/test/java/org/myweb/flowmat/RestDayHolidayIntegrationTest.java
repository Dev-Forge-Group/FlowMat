package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Work on a rest day paid like holiday work when the rule says so (docs/architecture/adr/ADR-028-pay-premiums.md decisions 16-17). */
@AutoConfigureMockMvc
class RestDayHolidayIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Rest days " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void workOnARestDayGetsTheHolidayPercentWhenTheRuleSaysSo() throws Exception {
        String kim = member();
        String lee = member();
        String rules = "/projects/" + projectId + "/labor-premiums";
        // Rest days are paid with the holiday percent, so one is needed.
        call(put(rules), Map.of("restDaysAsHolidays", true)).andExpect(status().isBadRequest());
        // kim works Monday to Friday 9 to 17 from the start; lee has no shifts.
        data(put("/projects/" + projectId + "/workers/" + kim + "/shifts"), Map.of("shifts",
            List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5)))));
        jdbc.update("update worker_schedule set effective_from = null where project_id = ? and user_id = ?", projectId, kim);
        for (String worker : List.of(kim, lee)) {
            data(put("/projects/" + projectId + "/workers/" + worker + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        }
        // 4 h on Friday 2026-09-04 and on Saturday 2026-09-05.
        attend(kim, "2026-09-04T09:00:00+09:00", "2026-09-04T13:00:00+09:00");
        attend(kim, "2026-09-05T09:00:00+09:00", "2026-09-05T13:00:00+09:00");
        attend(lee, "2026-09-05T09:00:00+09:00", "2026-09-05T13:00:00+09:00");

        // Holidays 100% more, rest days too.
        JsonNode saved = data(put(rules), Map.of("holidayPercent", 100, "restDaysAsHolidays", true)).path("current");
        assertTrue(saved.path("restDaysAsHolidays").asBoolean());
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);
        JsonNode kimPay = pay(kim);
        JsonNode friday = kimPay.path("days").get(0);
        assertFalse(friday.path("restDayHoliday").asBoolean(), kimPay.toString());
        assertMoney("0", friday.path("premiumMinutes"));
        JsonNode saturday = kimPay.path("days").get(1);
        assertEquals("2026-09-05", saturday.path("workDate").asText());
        assertTrue(saturday.path("restDayHoliday").asBoolean());
        assertFalse(saturday.path("holiday").asBoolean());
        assertMoney("240", saturday.path("premiumMinutes"));
        assertMoney("480", saturday.path("amount"));
        // Without shifts nothing says when lee rests.
        JsonNode leePay = pay(lee);
        assertFalse(leePay.path("days").get(0).path("restDayHoliday").asBoolean());
        assertMoney("0", leePay.path("premiumMinutes"));

        // A rule that does not say so leaves Saturday at the rate.
        JsonNode plainRule = data(put(rules), Map.of("holidayPercent", 100)).path("lines").get(0);
        assertFalse(plainRule.path("restDaysAsHolidays").asBoolean());
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ?", projectId);
        JsonNode plain = pay(kim);
        assertFalse(plain.path("days").get(1).path("restDayHoliday").asBoolean());
        assertMoney("0", plain.path("premiumMinutes"));
    }

    private JsonNode pay(String userId) throws Exception {
        return data(get("/projects/" + projectId + "/workers/" + userId + "/attendance-pay").param("from", "2026-09-04")
            .param("to", "2026-09-05"), null);
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
