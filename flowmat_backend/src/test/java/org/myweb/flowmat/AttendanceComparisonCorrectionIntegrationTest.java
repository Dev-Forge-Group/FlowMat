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

/**
 * The attendance comparison counts worked time a correction added on the run's original end, whenever the correction was approved
 * (docs/domain/worker-calendar.md AT7, docs/architecture/adr/ADR-028-pay-premiums.md decision 28), on real Postgres.
 */
@AutoConfigureMockMvc
class AttendanceComparisonCorrectionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Comparison corrections " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void correctedTimeCountsOnTheRunsOriginalEnd() throws Exception {
        String kim = member();
        String worker = "/projects/" + projectId + "/workers/" + kim;
        data(post(worker + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-01T08:00:00+09:00", "clockOut", "2026-09-01T12:00:00+09:00"));
        data(post(worker + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-03T08:00:00+09:00", "clockOut", "2026-09-03T10:00:00+09:00"));
        String workflow = data(post("/workflows"), Map.of("projectId", projectId, "workflowName", "Corrections " + UUID.randomUUID()))
            .path("workflowId").asText();
        String order = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderTitle", "Comparison corrections", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow, "workOrderId", order,
            "plannedOutputQty", 1)).path("productionRunId").asText();
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(), "userId", kim, "workedMinutes", 60));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
        // The run ended on 09-01, where its 60 minutes were recorded.
        jdbc.update("update production_run set actual_end_at = timestamptz '2026-09-01 18:00:00+09' where production_run_id = ?", run);
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 17:00:00+09' where production_run_id = ?", run);

        // 30 more minutes by a correction, approved on 09-03.
        String corrections = "/production-runs/" + run + "/corrections";
        String correction = data(post(corrections), Map.of("reason", "Kim stayed on", "lines",
            List.of(Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 30)))).path("productionRunCorrectionId").asText();
        call(post(corrections + "/" + correction + "/approve"), Map.of()).andExpect(status().isOk());
        assertEquals(1, jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-03 12:00:00+09' "
            + "where production_run_id = ? and worked_minutes = 30", run));

        JsonNode comparison = data(get(worker + "/attendance-comparison?from=2026-09-01&to=2026-09-03"), null);
        assertEquals(90, day(comparison, "2026-09-01").path("laborMinutes").asInt());
        assertEquals(150, day(comparison, "2026-09-01").path("differenceMinutes").asInt());
        assertEquals(0, day(comparison, "2026-09-03").path("laborMinutes").asInt());
        assertEquals(90, comparison.path("laborMinutes").asInt());
        // A range of 09-03 alone does not take it in.
        assertEquals(0, data(get(worker + "/attendance-comparison?from=2026-09-03&to=2026-09-03"), null).path("laborMinutes").asInt());
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
