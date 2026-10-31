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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
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

/**
 * Worked time with clock times (docs/domain/worker-calendar.md LB31) and the pay premiums in force at those times in labor cost
 * (docs/architecture/adr/ADR-028-pay-premiums.md decisions 23-26), on real Postgres.
 */
@AutoConfigureMockMvc
class WorkedTimePremiumIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Worked time " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void clockTimesAreCheckedAndKept() throws Exception {
        String kim = member();
        String run = openRun();
        String labor = "/production-runs/" + run + "/labor";
        // Both or neither, the end later, within a day, not in the future, at least the minutes, with an offset.
        for (Map<String, Object> wrong : List.of(
                timed(kim, null, "2026-09-01T09:00:00+09:00", null),
                timed(kim, null, "2026-09-01T11:00:00+09:00", "2026-09-01T09:00:00+09:00"),
                timed(kim, null, "2026-09-01T09:00:00+09:00", "2026-09-02T09:01:00+09:00"),
                timed(kim, null, "2041-09-01T09:00:00+09:00", "2041-09-01T11:00:00+09:00"),
                timed(kim, 121, "2026-09-01T09:00:00+09:00", "2026-09-01T11:00:00+09:00"),
                timed(kim, null, "2026-09-01 09:00", "2026-09-01T11:00:00+09:00"))) {
            call(post(labor), wrong).andExpect(status().isBadRequest());
        }
        // Nor can it end before the run started.
        call(post(labor), timed(kim, null, "2026-09-01T09:00:00+09:00", "2026-09-01T11:00:00+09:00")).andExpect(status().isBadRequest());
        startedAt(run, "2026-09-01 07:00:00+09");

        // Minutes left out are the whole minutes between; fewer leave out breaks.
        Map<String, Object> first = timed(kim, null, "2026-09-01T09:00:00+09:00", "2026-09-01T11:00:00+09:00");
        JsonNode line = data(post(labor), first).path("lines").get(0);
        assertEquals(120, line.path("workedMinutes").asInt());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), instant(line.path("workedFrom")));
        assertEquals(Instant.parse("2026-09-01T02:00:00Z"), instant(line.path("workedTo")));
        assertEquals(90, data(post(labor), timed(kim, 90, "2026-09-01T12:00:00+09:00", "2026-09-01T14:00:00+09:00"))
            .path("lines").get(1).path("workedMinutes").asInt());
        // The same record again answers as it is; other times under its key are refused.
        assertEquals(2, data(post(labor), first).path("lines").size());
        Map<String, Object> moved = new HashMap<>(first);
        moved.put("workedTo", "2026-09-01T10:30:00+09:00");
        call(post(labor), moved).andExpect(status().isConflict());
        // Without clock times it is as before.
        JsonNode plain = data(post(labor), Map.of("requestId", UUID.randomUUID().toString(), "userId", kim, "workedMinutes", 30))
            .path("lines").get(2);
        assertTrue(plain.path("workedFrom").isNull(), plain.toString());
    }

    @Test
    void timedRecordsTakeThePremiumsInForceThenAndTheOthersShareTheRest() throws Exception {
        String kim = member();
        String lee = member();
        data(put(worker(kim) + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        data(put(worker(lee) + "/labor-rates"), Map.of("hourlyRate", 30, "fromStart", true));
        data(post(worker(kim) + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-01T08:00:00+09:00", "clockOut", "2026-09-01T20:00:00+09:00"));
        data(post(worker(lee) + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-01T09:00:00+09:00", "clockOut", "2026-09-01T12:00:00+09:00"));
        data(put("/projects/" + projectId + "/labor-premiums"), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50,
            "nightStart", "19:00", "nightEnd", "06:00", "nightPercent", 50));
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-31' where project_id = ?", projectId);
        // kim's 720 minutes pay 870: 240 overtime from 16:00, 60 of them at night from 19:00.
        assertMoney("870", data(get(worker(kim) + "/attendance-pay?from=2026-09-01&to=2026-09-01"), null).path("paidMinutes"));

        String run = openRun();
        startedAt(run, "2026-09-01 07:00:00+09");
        String labor = "/production-runs/" + run + "/labor";
        data(post(labor), timed(kim, null, "2026-09-01T09:00:00+09:00", "2026-09-01T11:00:00+09:00"));
        data(post(labor), timed(kim, null, "2026-09-01T17:00:00+09:00", "2026-09-01T20:00:00+09:00"));
        data(post(labor), Map.of("requestId", UUID.randomUUID().toString(), "userId", kim, "workedMinutes", 240));
        data(post(labor), timed(lee, null, "2026-09-01T11:00:00+09:00", "2026-09-01T13:00:00+09:00"));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 18:00:00+09' where production_run_id = ? "
            + "and worked_from is null", run);

        JsonNode cost = data(get("/production-runs/" + run + "/labor-cost"), null);
        // 09:00-11:00 is regular time, priced on the day it started.
        JsonNode morning = line(cost, kim, 120);
        assertEquals("worked", morning.path("rateBasis").asText());
        assertEquals("2026-09-01", morning.path("rateDate").asText());
        assertMoney("1", morning.path("premiumFactor"));
        assertMoney("120", morning.path("premiumCost"));
        // 17:00-20:00 is overtime, its last hour at night: 120 min at 1.5 and 60 at 2.
        JsonNode evening = line(cost, kim, 180);
        assertMoney("180", evening.path("cost"));
        assertMoney("1.6667", evening.path("premiumFactor"));
        assertMoney("300", evening.path("premiumCost"));
        // The rest of the day, 420 minutes, pays 450: the overtime from 16:00 to 17:00 no timed record took.
        JsonNode untimed = line(cost, kim, 240);
        assertEquals("recorded", untimed.path("rateBasis").asText());
        assertMoney("1.0714", untimed.path("premiumFactor"));
        assertMoney("257.1429", untimed.path("premiumCost"));
        // lee's hour after 12:00 is outside attendance, at the rate, so lee's premiums are not all known.
        JsonNode leeLine = line(cost, lee, 120);
        assertMoney("1", leeLine.path("premiumFactor"));
        assertMoney("60", leeLine.path("premiumCost"));
        assertMoney("677.1429", person(cost, kim).path("premiumCost"));
        assertTrue(person(cost, kim).path("premiumsComplete").asBoolean());
        assertFalse(person(cost, lee).path("premiumsComplete").asBoolean());
        assertMoney("600", cost.path("cost"));
        assertMoney("737.1429", cost.path("premiumCost"));
        assertFalse(cost.path("premiumsComplete").asBoolean());
    }

    private String openRun() throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", projectId, "workflowName", "Worked time " + UUID.randomUUID()))
            .path("workflowId").asText();
        String order = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderTitle", "Worked time", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        return data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow, "workOrderId", order,
            "plannedOutputQty", 1)).path("productionRunId").asText();
    }

    /** The run as if it had started then, so worked time of that day can be recorded on it. */
    private void startedAt(String run, String at) {
        assertEquals(1, jdbc.update("update production_run set created_at = cast(? as timestamptz) where production_run_id = ?", at, run));
    }

    private static Map<String, Object> timed(String userId, Integer minutes, String from, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("userId", userId);
        if (minutes != null) body.put("workedMinutes", minutes);
        body.put("workedFrom", from);
        if (to != null) body.put("workedTo", to);
        return body;
    }

    private static Instant instant(JsonNode value) {
        return OffsetDateTime.parse(value.asText()).toInstant();
    }

    private static JsonNode line(JsonNode cost, String userId, int minutes) {
        for (JsonNode one : cost.path("lines")) {
            if (userId.equals(one.path("userId").asText()) && one.path("workedMinutes").asInt() == minutes) return one;
        }
        throw new AssertionError("No line of " + userId + " for " + minutes + " min in " + cost);
    }

    private static JsonNode person(JsonNode cost, String userId) {
        for (JsonNode one : cost.path("people")) {
            if (userId.equals(one.path("userId").asText())) return one;
        }
        throw new AssertionError("No " + userId + " in " + cost);
    }

    private String worker(String userId) {
        return "/projects/" + projectId + "/workers/" + userId;
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
