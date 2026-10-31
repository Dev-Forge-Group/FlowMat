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
import java.time.LocalDate;
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

/** The project owner's pay premiums and a worker's attendance as pay (docs/architecture/adr/ADR-028-pay-premiums.md), on real Postgres. */
@AutoConfigureMockMvc
class LaborPremiumIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Premiums " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        JsonNode zone = data(DEMO_OWNER, get("/projects/" + projectId + "/time-zone"), null);
        data(DEMO_OWNER, put("/projects/" + projectId + "/time-zone"),
            Map.of("timeZone", "Asia/Seoul", "expectedVersion", zone.path("version").asLong()));
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(DEMO_OWNER, delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @Test
    void onlyTheOwnerKeepsPremiumsFromTheirStartDates() throws Exception {
        String kim = member("editor");
        String path = "/projects/" + projectId + "/labor-premiums";
        JsonNode empty = data(DEMO_OWNER, get(path), null);
        LocalDate today = LocalDate.parse(empty.path("today").asText());
        assertTrue(empty.path("current").isNull(), empty.toString());
        call(kim, get(path), null).andExpect(status().isForbidden());
        call(kim, put(path), Map.of("holidayPercent", 50)).andExpect(status().isForbidden());

        JsonNode current = data(DEMO_OWNER, put(path), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50, "nightStart", "22:00",
            "nightEnd", "06:00", "nightPercent", 50, "holidayPercent", 100, "reason", "Labor law")).path("current");
        assertEquals(today.toString(), current.path("effectiveFrom").asText());
        assertEquals(480, current.path("overtimeAfterMinutes").asInt());
        assertEquals("22:00", current.path("nightStart").asText());
        assertEquals("06:00", current.path("nightEnd").asText());
        assertEquals(DEMO_OWNER, current.path("setBy").asText());

        // Half a premium, odd minutes, percents and times, and earlier dates are refused without a row.
        for (Map<String, Object> wrong : List.<Map<String, Object>>of(Map.of("overtimeAfterMinutes", 480), Map.of("overtimePercent", 50),
                Map.of("overtimeAfterMinutes", 0, "overtimePercent", 50), Map.of("overtimeAfterMinutes", 1441, "overtimePercent", 50),
                Map.of("holidayPercent", 0), Map.of("holidayPercent", 501), Map.of("nightStart", "22:00", "nightPercent", 50),
                Map.of("nightStart", "22:00", "nightEnd", "22:00", "nightPercent", 50),
                Map.of("nightStart", "25:00", "nightEnd", "06:00", "nightPercent", 50),
                Map.of("holidayPercent", 50, "effectiveFrom", today.minusDays(1).toString()))) {
            call(DEMO_OWNER, put(path), wrong).andExpect(status().isBadRequest());
        }
        mvc.perform(put(path).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"overtimeAfterMinutes\":480,\"overtimePercent\":2.5}"))
            .andExpect(status().isBadRequest());
        assertEquals(1, data(DEMO_OWNER, get(path), null).path("lines").size());

        // Saved twice for next week, the second without premiums: the later save counts; one that has not started is removed with
        // its earlier save, one that started stays.
        String later = today.plusDays(7).toString();
        data(DEMO_OWNER, put(path), Map.of("holidayPercent", 50, "effectiveFrom", later));
        JsonNode again = data(DEMO_OWNER, put(path), Map.of("effectiveFrom", later, "reason", "No premiums"));
        assertEquals("upcoming", again.path("lines").get(0).path("state").asText());
        assertTrue(again.path("lines").get(0).path("holidayPercent").isNull());
        assertEquals("replaced", again.path("lines").get(1).path("state").asText());
        assertEquals("current", again.path("lines").get(2).path("state").asText());
        call(DEMO_OWNER, delete(path + "/" + current.path("ruleId").asText()), null).andExpect(status().isConflict());
        JsonNode removed = data(DEMO_OWNER, delete(path + "/" + again.path("lines").get(0).path("ruleId").asText()), null);
        assertEquals("removed", removed.path("lines").get(0).path("state").asText());
        assertEquals("removed", removed.path("lines").get(1).path("state").asText());
        assertEquals(DEMO_OWNER, removed.path("lines").get(0).path("removedBy").asText());
        assertEquals(current.path("ruleId").asText(), removed.path("current").path("ruleId").asText());
    }

    @Test
    void attendanceIsPaidWithThePremiumsOfItsDay() throws Exception {
        String kim = member("editor");
        String worker = "/projects/" + projectId + "/workers/" + kim;
        data(DEMO_OWNER, put(worker + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        data(DEMO_OWNER, post("/holidays"), Map.of("projectId", projectId, "date", "2026-09-03", "name", "Test holiday"));
        // Tuesday 8 to 18, Wednesday 20 to Thursday 2, the holiday Thursday 9 to 19, and a cancelled hour on Tuesday evening.
        attend(kim, "2026-09-01T08:00:00+09:00", "2026-09-01T18:00:00+09:00");
        attend(kim, "2026-09-02T20:00:00+09:00", "2026-09-03T02:00:00+09:00");
        attend(kim, "2026-09-03T09:00:00+09:00", "2026-09-03T19:00:00+09:00");
        String wrong = attend(kim, "2026-09-01T19:00:00+09:00", "2026-09-01T20:00:00+09:00");
        data(DEMO_OWNER, post(worker + "/attendance/" + wrong + "/cancel"), Map.of("reason", "Not at work"));
        String pay = worker + "/attendance-pay";

        // Without premiums the attended minutes are paid at the rate: 1 560 minutes at 60 an hour.
        JsonNode plain = week(pay);
        assertEquals(3, plain.path("days").size(), plain.toString());
        assertEquals(1560, plain.path("attendedMinutes").asInt());
        assertMoney("0", plain.path("premiumMinutes"));
        assertMoney("1560", plain.path("amount"));
        assertTrue(plain.path("costComplete").asBoolean());

        // Premiums saved today leave earlier days as they were; the same rule from before those days pays them.
        data(DEMO_OWNER, put("/projects/" + projectId + "/labor-premiums"), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50,
            "nightStart", "22:00", "nightEnd", "06:00", "nightPercent", 50, "holidayPercent", 100));
        assertMoney("1560", week(pay).path("amount"));
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-31' where project_id = ?", projectId);
        JsonNode paid = week(pay);
        JsonNode tuesday = paid.path("days").get(0);
        assertEquals("2026-09-01", tuesday.path("workDate").asText());
        assertEquals(600, tuesday.path("attendedMinutes").asInt());
        assertEquals(120, tuesday.path("overtimeMinutes").asInt());
        assertEquals(0, tuesday.path("nightMinutes").asInt());
        assertFalse(tuesday.path("holiday").asBoolean());
        assertMoney("60", tuesday.path("premiumMinutes"));
        assertMoney("660", tuesday.path("amount"));
        // The night runs past midnight: 22 to 2 of the stretch that started on Wednesday counts for Wednesday.
        JsonNode wednesday = paid.path("days").get(1);
        assertEquals("2026-09-02", wednesday.path("workDate").asText());
        assertEquals(360, wednesday.path("attendedMinutes").asInt());
        assertEquals(240, wednesday.path("nightMinutes").asInt());
        assertEquals(0, wednesday.path("overtimeMinutes").asInt());
        assertMoney("480", wednesday.path("paidMinutes"));
        // On the holiday every minute gets 100% more and the overtime 50% on top.
        JsonNode thursday = paid.path("days").get(2);
        assertTrue(thursday.path("holiday").asBoolean());
        assertEquals(120, thursday.path("overtimeMinutes").asInt());
        assertMoney("660", thursday.path("premiumMinutes"));
        assertMoney("1260", thursday.path("amount"));
        assertMoney("60", thursday.path("hourlyRate"));
        assertMoney("840", paid.path("premiumMinutes"));
        assertMoney("2400", paid.path("paidMinutes"));
        assertMoney("2400", paid.path("amount"));

        // Without a rate the amount is unknown; only the owner sees pay; ranges follow attendance.
        String lee = member("editor");
        attend(lee, "2026-09-01T08:00:00+09:00", "2026-09-01T09:00:00+09:00");
        JsonNode unknown = data(DEMO_OWNER, get("/projects/" + projectId + "/workers/" + lee + "/attendance-pay")
            .param("from", "2026-09-01").param("to", "2026-09-01"), null);
        assertTrue(unknown.path("days").get(0).path("amount").isNull(), unknown.toString());
        assertFalse(unknown.path("costComplete").asBoolean());
        call(kim, get(pay), null).andExpect(status().isForbidden());
        call(DEMO_OWNER, get(pay).param("from", "2026-09-07").param("to", "2026-09-01"), null).andExpect(status().isBadRequest());
        call(DEMO_OWNER, get(pay).param("from", "2026-07-01").param("to", "2026-09-01"), null).andExpect(status().isBadRequest());
        call(DEMO_OWNER, get("/projects/" + projectId + "/workers/nobody/attendance-pay"), null).andExpect(status().isNotFound());
    }

    private JsonNode week(String pay) throws Exception {
        return data(DEMO_OWNER, get(pay).param("from", "2026-09-01").param("to", "2026-09-07"), null);
    }

    /** A closed stretch recorded by the owner; its id. */
    private String attend(String userId, String clockIn, String clockOut) throws Exception {
        data(DEMO_OWNER, post("/projects/" + projectId + "/workers/" + userId + "/attendance"),
            Map.of("requestId", UUID.randomUUID().toString(), "clockIn", clockIn, "clockOut", clockOut));
        return jdbc.queryForObject("select attendance_id from worker_attendance where project_id = ? and user_id = ? "
            + "and clock_in = cast(? as timestamptz)", String.class, projectId, userId, clockIn);
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), actual.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, role);
        return id;
    }

    private JsonNode data(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(user, request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(String user, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
