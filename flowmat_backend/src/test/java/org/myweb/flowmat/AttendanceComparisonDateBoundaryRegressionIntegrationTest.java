package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Date attribution and range boundaries from worker-calendar.md AT7 and ADR-028 decisions 27-28. */
@AutoConfigureMockMvc
class AttendanceComparisonDateBoundaryRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Comparison boundaries " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        JsonNode zone = data(get("/projects/" + projectId + "/time-zone"), null);
        data(put("/projects/" + projectId + "/time-zone"), Map.of("timeZone", "Asia/Seoul",
            "expectedVersion", zone.path("version").asLong()));
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @Test
    void workStartingOneMinuteBeforeTheRangeStaysOnItsStartDateAfterMidnight() throws Exception {
        String worker = member();
        attendance(worker, "2026-08-31");
        attendance(worker, "2026-09-01");
        String run = startRun();
        timed(run, worker, "2026-08-31T23:59:00+09:00", "2026-09-01T00:01:00+09:00");
        timed(run, worker, "2026-09-01T00:01:00+09:00", "2026-09-01T00:04:00+09:00");
        // Both records were submitted on the requested day; their worked start controls attribution.
        assertEquals(2, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 12:00:00+09' "
            + "where production_run_id = ?", run));

        assertMinutes(comparison(worker, "2026-08-31", "2026-08-31"), "2026-08-31", 2);
        assertMinutes(comparison(worker, "2026-09-01", "2026-09-01"), "2026-09-01", 3);
        JsonNode both = comparison(worker, "2026-08-31", "2026-09-01");
        assertEquals(2, day(both, "2026-08-31").path("laborMinutes").asInt());
        assertEquals(3, day(both, "2026-09-01").path("laborMinutes").asInt());
        assertEquals(5, both.path("laborMinutes").asInt());
    }

    @Test
    void timedAndUntimedQueriesCountEachRecordOnceAtInclusiveAndExclusiveMidnightBoundaries() throws Exception {
        String worker = member();
        attendance(worker, "2026-09-01");
        attendance(worker, "2026-09-02");
        String run = startRun();
        timed(run, worker, "2026-09-01T00:00:00+09:00", "2026-09-01T00:37:00+09:00");
        timed(run, worker, "2026-09-02T00:00:00+09:00", "2026-09-02T00:11:00+09:00");
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", worker, "workedMinutes", 13));
        // The first timed row is visible to both timestamp searches, but must contribute only 37 minutes.
        // The second starts exactly at the range's exclusive end, although it was recorded within the range.
        assertEquals(3, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 12:00:00+09' "
            + "where production_run_id = ?", run));

        assertMinutes(comparison(worker, "2026-09-01", "2026-09-01"), "2026-09-01", 50);
        assertMinutes(comparison(worker, "2026-09-02", "2026-09-02"), "2026-09-02", 11);
        JsonNode both = comparison(worker, "2026-09-01", "2026-09-02");
        assertEquals(50, day(both, "2026-09-01").path("laborMinutes").asInt());
        assertEquals(11, day(both, "2026-09-02").path("laborMinutes").asInt());
        assertEquals(61, both.path("laborMinutes").asInt());
    }

    @Test
    void correctionAddedOutsideTheRangeCountsAtOriginalMidnightEndAndIsNotDuplicated() throws Exception {
        String worker = member();
        attendance(worker, "2026-08-31");
        attendance(worker, "2026-09-01");
        attendance(worker, "2026-09-03");
        String run = startRun();
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", worker, "workedMinutes", 19));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
        assertEquals(1, jdbc.update("update production_run set actual_end_at = timestamptz '2026-09-01 00:00:00+09' "
            + "where production_run_id = ?", run));
        assertEquals(1, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-08-31 23:00:00+09' "
            + "where production_run_id = ?", run));

        addCorrection(run, worker, 7);
        assertEquals(1, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-03 12:00:00+09' "
            + "where production_run_id = ? and worked_minutes = 7", run));
        addCorrection(run, worker, 13);
        // This correction qualifies through both recorded_at and the original run end query.
        assertEquals(1, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 12:00:00+09' "
            + "where production_run_id = ? and worked_minutes = 13", run));

        assertMinutes(comparison(worker, "2026-09-01", "2026-09-01"), "2026-09-01", 20);
        assertMinutes(comparison(worker, "2026-09-03", "2026-09-03"), "2026-09-03", 0);
        // Untimed work recorded before the run ended retains its recorded date.
        assertMinutes(comparison(worker, "2026-08-31", "2026-08-31"), "2026-08-31", 19);
        JsonNode all = comparison(worker, "2026-08-31", "2026-09-03");
        assertEquals(19, day(all, "2026-08-31").path("laborMinutes").asInt());
        assertEquals(20, day(all, "2026-09-01").path("laborMinutes").asInt());
        assertEquals(0, day(all, "2026-09-03").path("laborMinutes").asInt());
        assertEquals(39, all.path("laborMinutes").asInt());
    }

    private void attendance(String worker, String date) throws Exception {
        data(post("/projects/" + projectId + "/workers/" + worker + "/attendance"), Map.of(
            "requestId", UUID.randomUUID().toString(), "clockIn", date + "T08:00:00+09:00", "clockOut", date + "T09:00:00+09:00"));
    }

    private String startRun() throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", projectId,
            "workflowName", "Boundary " + UUID.randomUUID())).path("workflowId").asText();
        String order = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderTitle", "Attendance boundaries", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderId", order, "plannedOutputQty", 1)).path("productionRunId").asText();
        assertEquals(1, jdbc.update("update production_run set created_at = timestamptz '2026-08-30 07:00:00+09' "
            + "where production_run_id = ?", run));
        return run;
    }

    private void timed(String run, String worker, String from, String to) throws Exception {
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", worker, "workedFrom", from, "workedTo", to));
    }

    private void addCorrection(String run, String worker, int minutes) throws Exception {
        String corrections = "/production-runs/" + run + "/corrections";
        String id = data(post(corrections), Map.of("reason", "Missing boundary work", "lines",
            List.of(Map.of("kind", "add_labor", "userId", worker, "workedMinutes", minutes))))
            .path("productionRunCorrectionId").asText();
        call(post(corrections + "/" + id + "/approve"), Map.of()).andExpect(status().isOk());
    }

    private JsonNode comparison(String worker, String first, String last) throws Exception {
        return data(get("/projects/" + projectId + "/workers/" + worker + "/attendance-comparison?from=" + first + "&to=" + last), null);
    }

    private static void assertMinutes(JsonNode comparison, String date, int laborMinutes) {
        assertEquals(60, comparison.path("attendedMinutes").asInt());
        assertEquals(laborMinutes, comparison.path("laborMinutes").asInt());
        JsonNode day = day(comparison, date);
        assertEquals(60, day.path("attendedMinutes").asInt());
        assertEquals(laborMinutes, day.path("laborMinutes").asInt());
        assertEquals(60 - laborMinutes, day.path("differenceMinutes").asInt());
    }

    private static JsonNode day(JsonNode comparison, String date) {
        for (JsonNode one : comparison.path("days")) {
            if (date.equals(one.path("date").asText())) return one;
        }
        throw new AssertionError("No " + date + " in " + comparison);
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER));
        assertEquals(1, jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, "editor"));
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
