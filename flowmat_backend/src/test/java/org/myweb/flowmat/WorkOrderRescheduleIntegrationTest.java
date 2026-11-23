package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WorkOrderRescheduleIntegrationTest extends IntegrationTestSupport {
    private static final String START = "2030-01-07T00:00:00Z";
    private static final String END = "2030-01-07T08:00:00Z";
    private static final String NEXT_START = "2030-01-08T00:00:00Z";
    private static final String NEXT_END = "2030-01-08T08:00:00Z";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void ownerChangesApprovedDatesAndRecordsTheOriginalPlanAndReason() throws Exception {
        String id = order("approved");
        data(call(post(path(id)), command(), DEMO_OWNER));
        call(get("/work-orders/" + id), null, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderStatus").value("approved"))
            .andExpect(jsonPath("$.data.plannedStartAt").value(NEXT_START));
        call(get(path(id)), null, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].previousPlannedStartAt").value(START))
            .andExpect(jsonPath("$.data[0].previousPlannedEndAt").value(END))
            .andExpect(jsonPath("$.data[0].plannedStartAt").value(NEXT_START))
            .andExpect(jsonPath("$.data[0].plannedEndAt").value(NEXT_END))
            .andExpect(jsonPath("$.data[0].reason").value("Supplier delay"))
            .andExpect(jsonPath("$.data[0].changedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data[0].changedAt").isNotEmpty());
    }

    @Test
    void runningOrderCanExtendEndButCannotChangeStart() throws Exception {
        String id = order("in_progress");
        jdbc.update("update work_order set actual_start_at = now() where work_order_id = ?", id);
        Map<String,Object> body = command();
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("plannedStartAt")));
        body.put("plannedStartAt", "2030-01-07T09:00:00+09:00");
        data(call(post(path(id)), body, DEMO_OWNER));
        assertEquals(1, count(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "completed", "cancelled"})
    void otherStatesRejectDedicatedRescheduling(String state) throws Exception {
        String id = order(state);
        call(post(path(id)), command(), DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(0, count(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"editor", "viewer", "outsider"})
    void nonOwnersCannotChangeDatesAndOutsidersCannotReadHistory(String role) throws Exception {
        String id = order("approved");
        String user = user(role);
        call(post(path(id)), command(), user).andExpect(status().isForbidden());
        call(get(path(id)), null, user).andExpect("outsider".equals(role) ? status().isForbidden() : status().isOk());
        assertEquals(0, count(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "bad\u0000reason"})
    void invalidReasonCannotLeaveAChangedOrder(String reason) throws Exception {
        String id = order("approved");
        Map<String,Object> body = command(); body.put("reason", reason);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("reason")));
        assertEquals(0, count(id));
        assertEquals(java.time.Instant.parse(START), jdbc.queryForObject(
            "select planned_start_at from work_order where work_order_id = ?", java.sql.Timestamp.class, id).toInstant());
    }

    @Test
    void reversedWindowAndStalePlanAreRejected() throws Exception {
        String id = order("approved");
        Map<String,Object> body = command(); body.put("plannedEndAt", START);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        body = command(); body.put("expectedPlannedStartAt", NEXT_START);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(0, count(id));
    }

    @Test
    void responseLossRetryRecordsOnceAndReusingKeyForDifferentCommandConflicts() throws Exception {
        String id = order("approved"); Map<String,Object> body = command();
        String change = data(call(post(path(id)), body, DEMO_OWNER)).path("change").path("changeId").asText();
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.change.changeId").value(change));
        body.put("reason", "Different request");
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isConflict());
        assertEquals(1, count(id));
    }

    @Test
    void missingDatesCanBePlannedAndNoOpDoesNotCreateHistory() throws Exception {
        String id = order("approved");
        jdbc.update("update work_order set planned_start_at = null, planned_end_at = null where work_order_id = ?", id);
        Map<String,Object> body = command(); body.put("expectedPlannedStartAt", null); body.put("expectedPlannedEndAt", null);
        data(call(post(path(id)), body, DEMO_OWNER));
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedPlannedStartAt", NEXT_START); body.put("expectedPlannedEndAt", NEXT_END);
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        assertEquals(1, count(id));
    }

    @Test
    void missingOrInvalidRequestIdCannotChangePlan() throws Exception {
        String id = order("approved"); Map<String,Object> body = command(); body.remove("requestId");
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        body.put("requestId", "invalid"); call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        assertEquals(0, count(id));
    }

    @Test
    void historyInsertFailureRollsBackTheChangedDates() throws Exception {
        String id = order("approved");
        jdbc.execute("alter table work_order_reschedule add constraint test_reschedule_rollback check (reason <> 'force-rollback')");
        try {
            Map<String,Object> body = command(); body.put("reason", "force-rollback");
            call(post(path(id)), body, DEMO_OWNER).andExpect(status().isConflict());
            assertEquals(java.time.Instant.parse(START), jdbc.queryForObject(
                "select planned_start_at from work_order where work_order_id = ?", java.sql.Timestamp.class, id).toInstant());
            assertEquals(0, count(id));
        } finally { jdbc.execute("alter table work_order_reschedule drop constraint test_reschedule_rollback"); }
    }

    @Test
    @Timeout(45)
    void aWaitingCommandSeesACommittedExecutionStartAndKeepsItsOriginalPlan() throws Exception {
        String id = order("approved");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var waiting = new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbc.queryForObject("select work_order_id from work_order where work_order_id = ? for update", String.class, id);
                jdbc.update("update work_order set work_order_status = 'in_progress', actual_start_at = now() where work_order_id = ?", id);
                var request = pool.submit(() -> call(post(path(id)), command(), DEMO_OWNER).andReturn().getResponse().getStatus());
                DatabaseContention.awaitWaitingOrDone(jdbc, request, jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return request;
            });
            assertEquals(409, waiting.get(15, TimeUnit.SECONDS));
            assertEquals(0, count(id));
        } finally { pool.shutdownNow(); }
    }

    @Test
    @Timeout(45)
    void simultaneousIdenticalRequestsCreateOneHistoryEntry() throws Exception {
        String id = order("approved"); Map<String,Object> body = command();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> call(post(path(id)), body, DEMO_OWNER).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> call(post(path(id)), body, DEMO_OWNER).andReturn().getResponse().getStatus());
            assertEquals(200, first.get(15, TimeUnit.SECONDS)); assertEquals(200, second.get(15, TimeUnit.SECONDS));
            assertEquals(1, count(id));
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plannedStartAt", "plannedEndAt", "expectedPlannedStartAt", "expectedPlannedEndAt"})
    void malformedDatesAreClientErrors(String field) throws Exception {
        String id = order("approved"); Map<String,Object> body = command(); body.put(field, "invalid date");
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isBadRequest());
        assertEquals(0, count(id));
    }

    @Test
    void clearingUnstartedDatesIsExplicitAndAuditFieldsCannotBeForged() throws Exception {
        String id = order("approved"); Map<String,Object> body = command();
        body.put("plannedStartAt", null); body.put("plannedEndAt", null); body.put("changedBy", "someone-else");
        call(post(path(id)), body, DEMO_OWNER).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.change.changedBy").value(DEMO_OWNER));
        assertEquals(1, count(id));
    }

    private String order(String state) throws Exception {
        String id = data(call(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workOrderTitle", "Reschedule " + UUID.randomUUID(),
            "targetQuantity", 1, "plannedStartAt", START, "plannedEndAt", END), DEMO_OWNER)).path("workOrderId").asText();
        jdbc.update("update work_order set work_order_status = ? where work_order_id = ?", state, id);
        return id;
    }
    private String user(String role) {
        String id = "reschedule-" + UUID.randomUUID().toString().substring(0,8);
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id = ?", id, id + "@test.local", DEMO_OWNER);
        if (!"outsider".equals(role)) jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }
    private Map<String,Object> command() {
        return new LinkedHashMap<>(Map.of("requestId", UUID.randomUUID().toString(), "plannedStartAt", NEXT_START,
            "plannedEndAt", NEXT_END, "expectedPlannedStartAt", START, "expectedPlannedEndAt", END, "reason", " Supplier delay "));
    }
    private String path(String id) { return "/work-orders/" + id + "/reschedules"; }
    private int count(String id) { return jdbc.queryForObject("select count(*) from work_order_reschedule where work_order_id = ?", Integer.class, id); }
    private ResultActions call(MockHttpServletRequestBuilder request, Map<String,?> body, String user) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(user));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
}
