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
 * Run and work order labor cost with the pay premiums: each record at its worker's pay over attendance on its day
 * (docs/architecture/adr/ADR-028-pay-premiums.md decisions 10-12), on real Postgres.
 */
@AutoConfigureMockMvc
class LaborCostPremiumIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void createProject() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Premium cost " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
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
    void recordsCostTheirWorkersPayOverAttendanceThatDay() throws Exception {
        String kim = member();
        String lee = member();
        data(put(worker(kim) + "/labor-rates"), Map.of("hourlyRate", 60, "fromStart", true));
        data(put(worker(lee) + "/labor-rates"), Map.of("hourlyRate", 30, "fromStart", true));
        // kim was at work from 8 to 18 on 2026-09-01; lee has no attendance.
        data(post(worker(kim) + "/attendance"), Map.of("requestId", UUID.randomUUID().toString(),
            "clockIn", "2026-09-01T08:00:00+09:00", "clockOut", "2026-09-01T18:00:00+09:00"));
        String workflow = data(post("/workflows"), Map.of("projectId", projectId, "workflowName", "Premium cost " + UUID.randomUUID()))
            .path("workflowId").asText();
        String order = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflow,
            "workOrderTitle", "Premium cost", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflow, "workOrderId", order,
            "plannedOutputQty", 1)).path("productionRunId").asText();
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(), "userId", kim, "workedMinutes", 120));
        data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(), "userId", lee, "workedMinutes", 60));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
        // Both were recorded that evening.
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 17:00:00+09' where production_run_id = ? "
            + "and user_id = ?", run, kim);
        jdbc.update("update production_run_labor set recorded_at = timestamptz '2026-09-01 17:30:00+09' where production_run_id = ? "
            + "and user_id = ?", run, lee);
        String cost = "/production-runs/" + run + "/labor-cost";

        // Without premiums kim's day pays its attendance, so the amounts stay; lee's day has no attendance to go by.
        JsonNode plain = data(get(cost), null);
        assertMoney("150", plain.path("cost"));
        assertMoney("150", plain.path("premiumCost"));
        assertMoney("1", plain.path("lines").get(0).path("premiumFactor"));
        assertTrue(plain.path("lines").get(1).path("premiumFactor").isNull(), plain.toString());
        assertFalse(plain.path("premiumsComplete").asBoolean());

        // Overtime after 8 h at 50% more, in force on that day: kim's 600 minutes pay 660, so each minute of the day costs 1.1.
        data(put("/projects/" + projectId + "/labor-premiums"), Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50));
        jdbc.update("update labor_premium_rule set effective_from = date '2026-08-31' where project_id = ?", projectId);
        JsonNode priced = data(get(cost), null);
        JsonNode kimLine = priced.path("lines").get(0);
        assertEquals(kim, kimLine.path("userId").asText());
        assertEquals("2026-09-01", kimLine.path("rateDate").asText());
        assertMoney("120", kimLine.path("cost"));
        assertMoney("1.1", kimLine.path("premiumFactor"));
        assertMoney("132", kimLine.path("premiumCost"));
        JsonNode leeLine = priced.path("lines").get(1);
        assertMoney("30", leeLine.path("cost"));
        assertMoney("30", leeLine.path("premiumCost"));
        assertMoney("132", priced.path("people").get(0).path("premiumCost"));
        assertTrue(priced.path("people").get(0).path("premiumsComplete").asBoolean());
        assertFalse(priced.path("people").get(1).path("premiumsComplete").asBoolean());
        assertMoney("150", priced.path("cost"));
        assertMoney("162", priced.path("premiumCost"));
        assertFalse(priced.path("premiumsComplete").asBoolean());

        JsonNode byOrder = data(get("/work-orders/" + order + "/labor-cost"), null);
        assertMoney("150", byOrder.path("cost"));
        assertMoney("162", byOrder.path("premiumCost"));
        assertFalse(byOrder.path("premiumsComplete").asBoolean());
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
