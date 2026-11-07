package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.catalog.repository.ProjectHolidayRepository;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerPayQuery;
import org.myweb.flowmat.domain.workforce.repository.WorkerAttendanceRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Follow-ups requested in WORKBOARD section 1 for ADR-028 decisions 10-12: correction dates, long work orders and more
 * recorded time than attendance. All writes target IntegrationTestSupport's disposable Postgres; the application services
 * and repository queries remain real. The spies only observe the period reads of one measured HTTP request.
 */
@AutoConfigureMockMvc
class LaborCostPremiumFollowupRegressionIntegrationTest extends IntegrationTestSupport {
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @MockitoSpyBean private WorkerPayQuery pay;
    @MockitoSpyBean private WorkerAttendanceRepository attendance;
    @MockitoSpyBean private ProjectHolidayRepository holidays;

    private String projectId;
    private String workflowId;

    @BeforeEach
    void createProjectAndPremiums() throws Exception {
        projectId = data(post("/projects"), Map.of("projectName", "Premium follow-up " + UUID.randomUUID(),
            "ownerId", DEMO_OWNER)).path("projectId").asText();
        JsonNode zone = data(get("/projects/" + projectId + "/time-zone"), null);
        data(put("/projects/" + projectId + "/time-zone"), Map.of("timeZone", PROJECT_ZONE.getId(),
            "expectedVersion", zone.path("version").asLong()));
        workflowId = data(post("/workflows"), Map.of("projectId", projectId,
            "workflowName", "No BOM premium follow-up " + UUID.randomUUID())).path("workflowId").asText();

        JsonNode saved = data(put("/projects/" + projectId + "/labor-premiums"),
            Map.of("overtimeAfterMinutes", 480, "overtimePercent", 50));
        assertEquals(1, saved.path("lines").size(), saved.toString());
        String ruleId = saved.path("lines").get(0).path("ruleId").asText();
        // As in LaborCostPremiumIntegrationTest, move only this new fixture rule into the fixed historical period.
        assertEquals(1, jdbc.update("update labor_premium_rule set effective_from = date '2026-01-01' "
            + "where rule_id = ? and project_id = ?", ruleId, projectId));
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (projectId != null) {
            call(delete("/projects/" + projectId), null).andExpect(status().isOk());
        }
    }

    @Test
    void correctionUsesPayOfTheOriginalRunEndDateInsteadOfTheDayItWasRecorded() throws Exception {
        String kim = workerAtSixtyPerHour();
        LocalDate endDay = LocalDate.of(2026, 9, 1);
        LocalDate recordedDay = LocalDate.of(2026, 9, 2);
        attend(kim, endDay, 18);       // 600 attended minutes pay 660: factor 1.1.
        attend(kim, recordedDay, 16);  // 480 attended minutes pay 480: factor 1.0.

        String order = order();
        String run = startRun(order);
        finish(run);
        // 00:30 in Seoul is still August 31 in UTC; the rate/pay date is the project's September 1.
        assertEquals(1, jdbc.update("update production_run set actual_end_at = cast(? as timestamptz) "
            + "where production_run_id = ?", "2026-09-01T00:30:00+09:00", run));
        JsonNode correction = data(post("/production-runs/" + run + "/corrections"), Map.of("reason", "Missed hour",
            "lines", List.of(Map.of("kind", "add_labor", "userId", kim, "workedMinutes", 60))));
        String correctionId = correction.path("productionRunCorrectionId").asText();
        JsonNode approved = data(post("/production-runs/" + run + "/corrections/" + correctionId + "/approve"), Map.of());
        assertEquals("applied", approved.path("status").asText(), approved.toString());
        assertEquals(1, approved.path("lines").size(), approved.toString());
        String addedId = approved.path("lines").get(0).path("createdRunLaborId").asText();
        assertFalse(addedId.isBlank(), approved.toString());
        recordedAt(addedId, recordedDay);

        JsonNode priced = data(get("/production-runs/" + run + "/labor-cost"), null);
        assertEquals(1, priced.path("lines").size(), priced.toString());
        JsonNode added = priced.path("lines").get(0);
        assertEquals(addedId, added.path("runLaborId").asText());
        assertEquals("run_end", added.path("rateBasis").asText());
        assertEquals("2026-09-01", added.path("rateDate").asText());
        assertEquals(60, added.path("workedMinutes").asInt());
        assertMoney("60", added.path("hourlyRate"));
        assertMoney("60", added.path("cost"));
        assertMoney("1.1", added.path("premiumFactor"));
        assertMoney("66", added.path("premiumCost"));
        assertTotals(priced, 60, "60", "66");
        assertTotals(person(priced, kim), 60, "60", "66");

        JsonNode byOrder = data(get("/work-orders/" + order + "/labor-cost"), null);
        assertEquals(1, byOrder.path("runs").asInt());
        assertTotals(byOrder, 60, "60", "66");
        assertTotals(person(byOrder, kim), 60, "60", "66");
    }

    @Test
    void aWorkOrderSpanningMoreThanSixtyTwoDaysReadsOnePayAndAttendancePeriodPerWorker() throws Exception {
        String kim = workerAtSixtyPerHour();
        String lee = workerAtSixtyPerHour();
        List<String> workers = List.of(kim, lee);
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 5, 10), LocalDate.of(2026, 9, 10));
        String order = order();
        for (LocalDate date : dates) {
            String run = startRun(order);
            for (String user : workers) {
                attend(user, date, 18);
                laborAt(run, user, 60, date);
            }
            finish(run);
        }
        LocalDate first = dates.get(0);
        LocalDate last = dates.get(2);
        // Fixture creation itself reads attendance. Measure just this one work-order labor-cost HTTP request.
        clearInvocations(pay, attendance, holidays);
        JsonNode priced = data(get("/work-orders/" + order + "/labor-cost"), null);

        assertEquals(3, priced.path("runs").asInt());
        assertEquals(2, priced.path("people").size(), priced.toString());
        assertTotals(priced, 360, "360", "396");
        assertTotals(person(priced, kim), 180, "180", "198");
        assertTotals(person(priced, lee), 180, "180", "198");

        for (String user : workers) {
            verify(pay, times(1)).paidDays(projectId, user, first, last);
            verify(attendance, times(1))
                .findAllByProjectIdAndUserIdAndWorkDateGreaterThanEqualAndWorkDateLessThanEqualOrderByClockInAscAttendanceIdAsc(
                    eq(projectId), eq(user), any(LocalDate.class), any(LocalDate.class));
        }
        verify(pay, times(2)).paidDays(eq(projectId), anyString(), any(LocalDate.class), any(LocalDate.class));

        ArgumentCaptor<LocalDate> attendanceFrom = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> attendanceTo = ArgumentCaptor.forClass(LocalDate.class);
        verify(attendance, times(2))
            .findAllByProjectIdAndUserIdAndWorkDateGreaterThanEqualAndWorkDateLessThanEqualOrderByClockInAscAttendanceIdAsc(
                eq(projectId), anyString(), attendanceFrom.capture(), attendanceTo.capture());
        // Weekly pay can legitimately load earlier days. Require coverage and one read, not its internal lookback length.
        for (LocalDate from : attendanceFrom.getAllValues()) {
            assertFalse(from.isAfter(first), from.toString());
        }
        for (LocalDate to : attendanceTo.getAllValues()) {
            assertFalse(to.isBefore(last), to.toString());
        }

        ArgumentCaptor<LocalDate> holidayFrom = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> holidayTo = ArgumentCaptor.forClass(LocalDate.class);
        // Holidays belong to the project: sharing that read, or skipping it without a holiday premium, is also valid.
        verify(holidays, atMost(workers.size())).findAllByProjectIdAndHolidayDateBetweenAndDeletedYn(
            eq(projectId), holidayFrom.capture(), holidayTo.capture(), eq("N"));
        for (LocalDate from : holidayFrom.getAllValues()) {
            assertFalse(from.isAfter(first), from.toString());
        }
        for (LocalDate to : holidayTo.getAllValues()) {
            assertFalse(to.isBefore(last), to.toString());
        }
    }

    @Test
    void moreRecordedMinutesThanAttendanceKeepTheSameDailyFactorAcrossRuns() throws Exception {
        String kim = workerAtSixtyPerHour();
        LocalDate date = LocalDate.of(2026, 9, 1);
        attend(kim, date, 18); // 600 attended minutes, 660 paid; both runs still use factor 1.1.
        String order = order();
        String firstRun = startRun(order);
        laborAt(firstRun, kim, 720, date);
        finish(firstRun);
        String secondRun = startRun(order);
        laborAt(secondRun, kim, 480, date);
        finish(secondRun);

        JsonNode first = data(get("/production-runs/" + firstRun + "/labor-cost"), null);
        assertEquals(1, first.path("lines").size(), first.toString());
        assertMoney("1.1", first.path("lines").get(0).path("premiumFactor"));
        assertMoney("792", first.path("lines").get(0).path("premiumCost"));
        assertTotals(first, 720, "720", "792");
        assertTotals(person(first, kim), 720, "720", "792");

        JsonNode second = data(get("/production-runs/" + secondRun + "/labor-cost"), null);
        assertEquals(1, second.path("lines").size(), second.toString());
        assertMoney("1.1", second.path("lines").get(0).path("premiumFactor"));
        assertMoney("528", second.path("lines").get(0).path("premiumCost"));
        assertTotals(second, 480, "480", "528");
        assertTotals(person(second, kim), 480, "480", "528");

        JsonNode byOrder = data(get("/work-orders/" + order + "/labor-cost"), null);
        assertEquals(2, byOrder.path("runs").asInt());
        assertTotals(byOrder, 1200, "1200", "1320");
        assertTotals(person(byOrder, kim), 1200, "1200", "1320");
    }

    private String workerAtSixtyPerHour() throws Exception {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), projectId, id, "editor");
        data(put("/projects/" + projectId + "/workers/" + id + "/labor-rates"),
            Map.of("hourlyRate", 60, "fromStart", true));
        return id;
    }

    private void attend(String userId, LocalDate date, int endHour) throws Exception {
        data(post("/projects/" + projectId + "/workers/" + userId + "/attendance"),
            Map.of("requestId", UUID.randomUUID().toString(), "clockIn", date + "T08:00:00+09:00",
                "clockOut", date + "T" + endHour + ":00:00+09:00"));
    }

    private String order() throws Exception {
        String id = data(post("/work-orders"), Map.of("projectId", projectId, "workflowId", workflowId,
            "workOrderTitle", "Premium follow-up", "targetQuantity", 1)).path("workOrderId").asText();
        call(post("/work-orders/" + id + "/approve"), null).andExpect(status().isOk());
        return id;
    }

    private String startRun(String order) throws Exception {
        return data(post("/production-runs/start"), Map.of("projectId", projectId, "workflowId", workflowId,
            "workOrderId", order, "plannedOutputQty", 1, "runType", "actual")).path("productionRunId").asText();
    }

    private void finish(String run) throws Exception {
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 0)).andExpect(status().isOk());
    }

    private void laborAt(String run, String userId, int minutes, LocalDate date) throws Exception {
        String note = "Historical fixture " + UUID.randomUUID();
        JsonNode recorded = data(post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", userId, "workedMinutes", minutes, "note", note));
        for (JsonNode line : recorded.path("lines")) {
            if (note.equals(line.path("note").asText())) {
                recordedAt(line.path("runLaborId").asText(), date);
                return;
            }
        }
        throw new AssertionError("The labor response did not include the new fixture record: " + recorded);
    }

    private void recordedAt(String laborId, LocalDate date) {
        assertEquals(1, jdbc.update("update production_run_labor set recorded_at = cast(? as timestamptz) "
            + "where run_labor_id = ? and project_id = ?", date + "T17:00:00+09:00", laborId, projectId));
    }

    private static JsonNode person(JsonNode priced, String userId) {
        for (JsonNode row : priced.path("people")) {
            if (userId.equals(row.path("userId").asText())) {
                return row;
            }
        }
        throw new AssertionError("No cost for worker " + userId + ": " + priced);
    }

    private static void assertTotals(JsonNode priced, int minutes, String base, String premium) {
        assertEquals(minutes, priced.path("workedMinutes").asInt(), priced.toString());
        assertMoney(base, priced.path("cost"));
        assertMoney(premium, priced.path("premiumCost"));
        assertTrue(priced.path("costComplete").asBoolean(), priced.toString());
        assertTrue(priced.path("premiumsComplete").asBoolean(), priced.toString());
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertTrue(actual.isNumber(), actual.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), actual.toString());
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        return mvc.perform(request);
    }
}
