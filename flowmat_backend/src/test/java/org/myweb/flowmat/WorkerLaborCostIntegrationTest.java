package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Worker rates and labor cost for the project owner only (docs/architecture/adr/ADR-025-worker-labor-cost.md), on real Postgres. */
@AutoConfigureMockMvc
class WorkerLaborCostIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onlyTheOwnerKeepsAWorkersRatesFromTheirStartDates() throws Exception {
        String kim = member(DEMO_PROJECT, "editor");
        String path = "/projects/" + DEMO_PROJECT + "/workers/" + kim + "/labor-rates";
        JsonNode empty = data(DEMO_OWNER, get(path), null);
        LocalDate today = LocalDate.parse(empty.path("today").asText());
        assertTrue(empty.path("current").isNull(), empty.toString());

        JsonNode first = data(DEMO_OWNER, put(path), Map.of("hourlyRate", 30, "reason", "Base"));
        assertMoney("30", first.path("current").path("hourlyRate"));
        assertEquals(today.toString(), first.path("current").path("effectiveFrom").asText());
        assertEquals(DEMO_OWNER, first.path("current").path("setBy").asText());

        // A raise from next week, saved twice: the later save counts for that start (ADR-025 decision 3).
        String later = today.plusDays(7).toString();
        data(DEMO_OWNER, put(path), Map.of("hourlyRate", 33.5, "effectiveFrom", later, "reason", "Raise"));
        JsonNode raised = data(DEMO_OWNER, put(path), Map.of("hourlyRate", 34, "effectiveFrom", later, "reason", "Raise, corrected"));
        assertEquals(3, raised.path("lines").size(), raised.toString());
        assertEquals("upcoming", raised.path("lines").get(0).path("state").asText());
        assertMoney("34", raised.path("lines").get(0).path("hourlyRate"));
        assertEquals("replaced", raised.path("lines").get(1).path("state").asText());
        assertEquals("current", raised.path("lines").get(2).path("state").asText());
        assertMoney("30", raised.path("current").path("hourlyRate"));

        // Earlier dates keep their rate; a rate that started stays; one that has not started is removed with its earlier saves.
        call(DEMO_OWNER, put(path), Map.of("hourlyRate", 31, "effectiveFrom", today.minusDays(1).toString()))
            .andExpect(status().isBadRequest());
        call(DEMO_OWNER, delete(path + "/" + raised.path("current").path("rateId").asText()), null).andExpect(status().isConflict());
        JsonNode removed = data(DEMO_OWNER, delete(path + "/" + raised.path("lines").get(0).path("rateId").asText()), null);
        assertEquals("removed", removed.path("lines").get(0).path("state").asText());
        assertEquals("removed", removed.path("lines").get(1).path("state").asText());
        assertEquals(DEMO_OWNER, removed.path("lines").get(0).path("removedBy").asText());
        assertMoney("30", removed.path("current").path("hourlyRate"));

        // Unknown is not zero; bad amounts and bodies are refused without a row.
        JsonNode unknown = data(DEMO_OWNER, put(path), Map.of("effectiveFrom", today.plusDays(3).toString()));
        assertTrue(unknown.path("lines").get(0).path("hourlyRate").isNull());
        call(DEMO_OWNER, put(path), Map.of("hourlyRate", -1)).andExpect(status().isBadRequest());
        call(DEMO_OWNER, put(path), Map.of("hourlyRate", new BigDecimal("1.23456"))).andExpect(status().isBadRequest());
        call(DEMO_OWNER, put(path), Map.of("hourlyRate", new BigDecimal("12345678901"))).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"hourlyRate\":\"a lot\"}")).andExpect(status().isBadRequest());
        assertEquals(4, data(DEMO_OWNER, get(path), null).path("lines").size());

        // Nobody but the owner sees or sets rates: not an editor, a viewer, the worker or an outsider (decision 1).
        String viewer = member(DEMO_PROJECT, "viewer");
        for (String user : List.of(member(DEMO_PROJECT, "editor"), viewer, kim, "unrelated-user")) {
            call(user, get(path), null).andExpect(status().isForbidden());
            call(user, put(path), Map.of("hourlyRate", 1)).andExpect(status().isForbidden());
        }
        call(DEMO_OWNER, get("/projects/" + DEMO_PROJECT + "/workers/not-a-member/labor-rates"), null).andExpect(status().isNotFound());

        // Rates belong to one project: the same person elsewhere has none (decision 2).
        String other = data(DEMO_OWNER, post("/projects"), Map.of("projectName", "Rates " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), other, kim, "editor");
        JsonNode elsewhere = data(DEMO_OWNER, get("/projects/" + other + "/workers/" + kim + "/labor-rates"), null);
        assertTrue(elsewhere.path("current").isNull());
        assertEquals(0, elsewhere.path("lines").size());
    }

    @Test
    void workedTimeIsPricedAtTheRateOfItsDayForTheOwnerOnly() throws Exception {
        String kim = member(DEMO_PROJECT, "editor");
        String lee = member(DEMO_PROJECT, "editor");
        // kim's first rate, 20, covers the time before it (decision 11); 30 from today; lee has no rate.
        String rates = "/projects/" + DEMO_PROJECT + "/workers/" + kim + "/labor-rates";
        call(DEMO_OWNER, put(rates), Map.of("hourlyRate", 20, "fromStart", true, "effectiveFrom", "2041-09-01"))
            .andExpect(status().isBadRequest());
        JsonNode first = data(DEMO_OWNER, put(rates), Map.of("hourlyRate", 20, "fromStart", true, "reason", "Before"));
        assertTrue(first.path("current").path("effectiveFrom").isNull(), first.toString());
        data(DEMO_OWNER, put(rates), Map.of("hourlyRate", 30));
        // Only the first rate can reach back.
        call(DEMO_OWNER, put(rates), Map.of("hourlyRate", 25, "fromStart", true)).andExpect(status().isConflict());
        String order = data(DEMO_OWNER, post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Labor cost " + UUID.randomUUID(), "targetQuantity", 1)).path("workOrderId").asText();
        call(DEMO_OWNER, post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", order, "plannedOutputQty", 1)).path("productionRunId").asText();
        data(DEMO_OWNER, post("/production-runs/" + run + "/labor"), worked(kim, 90));
        data(DEMO_OWNER, post("/production-runs/" + run + "/labor"), worked(lee, 30));

        String cost = "/production-runs/" + run + "/labor-cost";
        JsonNode priced = data(DEMO_OWNER, get(cost), null);
        assertEquals(120, priced.path("workedMinutes").asInt());
        assertMoney("45", priced.path("cost"));
        assertEquals(false, priced.path("costComplete").asBoolean());
        assertEquals(kim, priced.path("people").get(0).path("userId").asText());
        assertMoney("45", priced.path("people").get(0).path("cost"));
        assertEquals(true, priced.path("people").get(0).path("costComplete").asBoolean());
        assertMoney("0", priced.path("people").get(1).path("cost"));
        assertEquals(false, priced.path("people").get(1).path("costComplete").asBoolean());
        assertEquals("recorded", priced.path("lines").get(0).path("rateBasis").asText());
        assertMoney("30", priced.path("lines").get(0).path("hourlyRate"));
        assertTrue(priced.path("lines").get(1).path("cost").isNull());

        // Others get no amounts: the cost answers refuse them and the worked-time answer has no amount fields (decision 6).
        String viewer = member(DEMO_PROJECT, "viewer");
        for (String user : List.of(kim, viewer)) {
            call(user, get(cost), null).andExpect(status().isForbidden());
            call(user, get("/work-orders/" + order + "/labor-cost"), null).andExpect(status().isForbidden());
        }
        call(viewer, get("/production-runs/" + run + "/labor"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cost").doesNotExist())
            .andExpect(jsonPath("$.data.lines[0].cost").doesNotExist())
            .andExpect(jsonPath("$.data.lines[0].hourlyRate").doesNotExist());

        // Time a correction adds after the run ended yesterday is priced at yesterday's rate (decision 5).
        call(DEMO_OWNER, post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        jdbc.update("update production_run set actual_end_at = actual_end_at - interval '1 day' where production_run_id = ?", run);
        String correction = data(DEMO_OWNER, post("/production-runs/" + run + "/corrections"), Map.of("reason", "Missed hour",
            "lines", List.of(Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 60)))).path("productionRunCorrectionId").asText();
        data(DEMO_OWNER, post("/production-runs/" + run + "/corrections/" + correction + "/approve"), Map.of());
        JsonNode corrected = data(DEMO_OWNER, get(cost), null);
        JsonNode added = corrected.path("lines").get(2);
        assertEquals("run_end", added.path("rateBasis").asText());
        assertMoney("20", added.path("hourlyRate"));
        assertMoney("20", added.path("cost"));
        assertMoney("65", corrected.path("people").get(0).path("cost"));

        JsonNode byOrder = data(DEMO_OWNER, get("/work-orders/" + order + "/labor-cost"), null);
        assertEquals(1, byOrder.path("runs").asInt());
        assertEquals(180, byOrder.path("workedMinutes").asInt());
        assertMoney("65", byOrder.path("cost"));
        assertEquals(false, byOrder.path("costComplete").asBoolean());
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), actual.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private static Map<String, Object> worked(String userId, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("userId", userId);
        body.put("workedMinutes", minutes);
        return body;
    }

    /** A new member of a project. */
    private String member(String project, String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), project, id, role);
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
