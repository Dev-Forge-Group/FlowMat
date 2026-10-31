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

/** A worker's own pay premiums over the project's (docs/architecture/adr/ADR-028-pay-premiums.md decisions 20-22), on real Postgres. */
@AutoConfigureMockMvc
class WorkerPremiumIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Worker premiums " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void aWorkersOwnPremiumsWinOverTheProjectsUntilTheyFollowItAgain() throws Exception {
        String kim = member();
        String lee = member();
        String project = "/projects/" + projectId + "/labor-premiums";
        String kimRules = "/projects/" + projectId + "/workers/" + kim + "/labor-premiums";
        // Only the owner, for a worker of the project; following the project is for workers and has no premiums.
        call(kim, get(kimRules), null).andExpect(status().isForbidden());
        call(DEMO_OWNER, get("/projects/" + projectId + "/workers/nobody/labor-premiums"), null).andExpect(status().isNotFound());
        call(DEMO_OWNER, put(project), Map.of("followProject", true)).andExpect(status().isBadRequest());
        call(DEMO_OWNER, put(kimRules), Map.of("followProject", true, "holidayPercent", 50)).andExpect(status().isBadRequest());
        JsonNode none = data(DEMO_OWNER, get(kimRules), null);
        assertEquals(kim, none.path("userId").asText());
        assertTrue(none.path("current").isNull(), none.toString());
        LocalDate today = LocalDate.parse(none.path("today").asText());

        // The project pays overtime after 8 h at 50% more; kim, a manager, gets none, then follows the project from the next day.
        data(DEMO_OWNER, put(project), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50));
        JsonNode own = data(DEMO_OWNER, put(kimRules), Map.of("reason", "Manager"));
        assertEquals("Manager", own.path("current").path("reason").asText());
        assertTrue(own.path("current").path("overtimeAfterMinutes").isNull());
        JsonNode follows = data(DEMO_OWNER, put(kimRules), Map.of("followProject", true, "effectiveFrom", today.plusDays(1).toString()));
        assertTrue(follows.path("lines").get(0).path("followProject").asBoolean());
        // The project's history holds only its own saves.
        assertEquals(1, data(DEMO_OWNER, get(project), null).path("lines").size());

        // Moved back: the project's rule and kim's from 2026-08-30, kim following the project again from 2026-09-02.
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-30' where project_id = ? and follow_project_yn = 'N'",
            projectId);
        jdbc.update("update labor_premium_rule set effective_from = date '2026-09-02' where project_id = ? and follow_project_yn = 'Y'",
            projectId);
        for (String worker : List.of(kim, lee)) {
            data(DEMO_OWNER, put("/projects/" + projectId + "/workers/" + worker + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
            attend(worker, "2026-09-01T08:00:00+09:00", "2026-09-01T18:00:00+09:00");
            attend(worker, "2026-09-02T08:00:00+09:00", "2026-09-02T18:00:00+09:00");
        }
        JsonNode kimPay = pay(kim);
        assertMoney("0", kimPay.path("days").get(0).path("premiumMinutes"));
        assertMoney("60", kimPay.path("days").get(1).path("premiumMinutes"));
        JsonNode leePay = pay(lee);
        assertMoney("60", leePay.path("days").get(0).path("premiumMinutes"));
        assertMoney("60", leePay.path("days").get(1).path("premiumMinutes"));

        // An own save that has not started is removed; one that started stays.
        JsonNode upcoming = data(DEMO_OWNER, put(kimRules), Map.of("holidayPercent", 100, "effectiveFrom", today.plusDays(7).toString()));
        JsonNode removed = data(DEMO_OWNER, delete(kimRules + "/" + upcoming.path("lines").get(0).path("ruleId").asText()), null);
        assertEquals("removed", removed.path("lines").get(0).path("state").asText());
        assertTrue(removed.path("current").path("followProject").asBoolean(), removed.toString());
        String manager = null;
        for (JsonNode line : removed.path("lines")) {
            if ("Manager".equals(line.path("reason").asText())) {
                manager = line.path("ruleId").asText();
            }
        }
        call(DEMO_OWNER, delete(kimRules + "/" + manager), null).andExpect(status().isConflict());
    }

    private JsonNode pay(String userId) throws Exception {
        return data(DEMO_OWNER, get("/projects/" + projectId + "/workers/" + userId + "/attendance-pay").param("from", "2026-09-01")
            .param("to", "2026-09-02"), null);
    }

    private void attend(String userId, String clockIn, String clockOut) throws Exception {
        data(DEMO_OWNER, post("/projects/" + projectId + "/workers/" + userId + "/attendance"),
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
