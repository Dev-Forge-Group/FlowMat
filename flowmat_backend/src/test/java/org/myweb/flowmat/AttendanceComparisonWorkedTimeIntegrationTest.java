package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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
 * The attendance comparison counts worked time with clock times on the day it started, and the rest on the day it was recorded
 * (docs/domain/worker-calendar.md AT7, docs/architecture/adr/ADR-028-pay-premiums.md decision 27), on real Postgres.
 */
@AutoConfigureMockMvc
class AttendanceComparisonWorkedTimeIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Comparison times " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void timedWorkCountsOnTheDayItStartedAndTheRestOnTheDayItWasRecorded() throws Exception {
        String kim = member();
        String worker = "/projects/" + projectId + "/workers/" + kim;
        data(post(worker + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-01T08:00:00+09:00", "clockOut", "2026-09-01T12:00:00+09:00"));
        data(post(worker + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-02T08:00:00+09:00", "clockOut", "2026-09-02T10:00:00+09:00"));
        String workflow = data(post("/workflows"), Map.of("projectId", projectId, "workflowName", "Comparison " + UUID.randomUUID()))
            .path("workflowId").asText();
        String order = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderTitle", "Comparison times", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow, "workOrderId", order,
            "plannedOutputQty", 1)).path("productionRunId").asText();
        jdbc.update("update production_run set created_at = timestamptz '2026-08-31 07:00:00+09' where production_run_id = ?", run);
        String labor = "/production-runs/" + run + "/labor";
        // 120 minutes worked on 09-01, recorded today.
        data(post(labor), timed(kim, "2026-09-01T09:00:00+09:00", "2026-09-01T11:00:00+09:00"));
        // 60 minutes from 23:30 on 09-02 past midnight: the day it started.
        data(post(labor), timed(kim, "2026-09-02T23:30:00+09:00", "2026-09-03T00:30:00+09:00"));
        // 60 minutes worked on 08-31 but recorded on 09-01: not in the range.
        data(post(labor), timed(kim, "2026-08-31T10:00:00+09:00", "2026-08-31T11:00:00+09:00"));
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 12:00:00+09' where production_run_id = ? "
            + "and worked_from = timestamptz '2026-08-31 10:00:00+09'", run);
        // 30 minutes without clock times, recorded on 09-02.
        data(post(labor), Map.of("requestId", UUID.randomUUID().toString(), "userId", kim, "workedMinutes", 30));
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-02 12:00:00+09' where production_run_id = ? "
            + "and worked_from is null", run);

        JsonNode comparison = data(get(worker + "/attendance-comparison?from=2026-09-01&to=2026-09-02"), null);
        JsonNode first = day(comparison, "2026-09-01");
        assertEquals(240, first.path("attendedMinutes").asInt());
        assertEquals(120, first.path("laborMinutes").asInt());
        assertEquals(120, first.path("differenceMinutes").asInt());
        JsonNode second = day(comparison, "2026-09-02");
        assertEquals(120, second.path("attendedMinutes").asInt());
        assertEquals(90, second.path("laborMinutes").asInt());
        assertEquals(30, second.path("differenceMinutes").asInt());
        assertEquals(360, comparison.path("attendedMinutes").asInt());
        assertEquals(210, comparison.path("laborMinutes").asInt());
    }

    private static Map<String, Object> timed(String userId, String from, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("userId", userId);
        body.put("workedFrom", from);
        body.put("workedTo", to);
        return body;
    }

    private static JsonNode day(JsonNode comparison, String date) {
        for (JsonNode one : comparison.path("days")) {
            if (date.equals(one.path("date").asText())) return one;
        }
        throw new AssertionError("No " + date + " in " + comparison);
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
