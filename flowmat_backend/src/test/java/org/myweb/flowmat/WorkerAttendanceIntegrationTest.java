package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

/** Workers' attendance and its comparison with worked time (docs/architecture/adr/ADR-026-attendance.md), on real Postgres. */
@AutoConfigureMockMvc
class WorkerAttendanceIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aWorkerOrTheOwnerRecordsStretchesThatNeverOverlapAndCancelsWithAReason() throws Exception {
        String kim = member("editor");
        String base = "/projects/" + DEMO_PROJECT + "/workers/" + kim + "/attendance";
        ZoneId zone = zone();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime yesterdayIn = now.minusHours(30);
        LocalDate first = yesterdayIn.atZoneSameInstant(zone).toLocalDate();
        OffsetDateTime todayIn = now.minusHours(2);
        LocalDate second = todayIn.atZoneSameInstant(zone).toLocalDate();

        // The owner records a closed stretch; the same request again answers as it is, other contents under its key are refused.
        Map<String, Object> closed = stretch(yesterdayIn, now.minusHours(26), "Day");
        data(DEMO_OWNER, post(base), closed);
        data(DEMO_OWNER, post(base), closed);
        Map<String, Object> changed = new HashMap<>(closed);
        changed.put("note", "Other");
        call(DEMO_OWNER, post(base), changed).andExpect(status().isConflict());
        call(DEMO_OWNER, post(base), stretch(yesterdayIn.plusHours(1), yesterdayIn.plusHours(2), null)).andExpect(status().isConflict());

        // kim clocks in; a second open stretch is refused; kim clocks out.
        JsonNode open = data(kim, post(base), stretch(todayIn, null, null));
        String openId = open.path("open").path("attendanceId").asText();
        assertEquals(todayIn.toInstant(), OffsetDateTime.parse(open.path("open").path("clockIn").asText()).toInstant());
        call(kim, post(base), stretch(now.minusHours(1), null, null)).andExpect(status().isConflict());
        JsonNode out = data(kim, post(base + "/" + openId + "/clock-out"), Map.of("clockOut", now.minusHours(1).toString()));
        assertTrue(out.path("open").isNull(), out.toString());
        call(kim, post(base + "/" + openId + "/clock-out"), Map.of()).andExpect(status().isConflict());

        JsonNode range = data(kim, get(base).param("from", first.toString()).param("to", second.toString()), null);
        assertEquals(240, attended(range, first));
        assertEquals(60, attended(range, second));
        assertEquals(2, range.path("lines").size());

        // Times are checked before anything is saved.
        call(kim, post(base), stretch(now.plusHours(1), null, null)).andExpect(status().isBadRequest());
        call(kim, post(base), stretch(now.minusHours(5), now.minusHours(6), null)).andExpect(status().isBadRequest());
        call(kim, post(base), stretch(now.minusHours(60), now.minusHours(35), null)).andExpect(status().isBadRequest());
        call(kim, post(base), Map.of("requestId", UUID.randomUUID().toString(), "clockIn", "yesterday")).andExpect(status().isBadRequest());
        call(kim, post(base), Map.of("requestId", "not-a-uuid", "clockIn", now.minusHours(10).toString())).andExpect(status().isBadRequest());

        // A cancel needs a reason, keeps the row and no longer counts.
        String day = range.path("lines").get(0).path("attendanceId").asText();
        call(kim, post(base + "/" + day + "/cancel"), Map.of()).andExpect(status().isBadRequest());
        JsonNode cancelled = data(kim, post(base + "/" + day + "/cancel"), Map.of("reason", "Wrong day"));
        assertTrue(cancelled.toString().contains("Wrong day"), cancelled.toString());
        call(kim, post(base + "/" + day + "/cancel"), Map.of("reason", "Again")).andExpect(status().isConflict());
        JsonNode after = data(kim, get(base).param("from", first.toString()).param("to", second.toString()), null);
        assertEquals(0, attended(after, first));
        assertTrue(after.path("lines").get(0).path("cancelled").asBoolean(), after.toString());

        // Only kim and the owner: not another editor, a viewer or an outsider; a viewer cannot record their own.
        String viewer = member("viewer");
        for (String user : List.of(member("editor"), viewer, "unrelated-user")) {
            call(user, get(base), null).andExpect(status().isForbidden());
            call(user, post(base), stretch(now.minusHours(12), null, null)).andExpect(status().isForbidden());
        }
        data(DEMO_OWNER, get(base), null);
        call(DEMO_OWNER, get("/projects/" + DEMO_PROJECT + "/workers/not-a-member/attendance"), null).andExpect(status().isNotFound());
        call(viewer, post("/projects/" + DEMO_PROJECT + "/workers/" + viewer + "/attendance"), stretch(now.minusHours(3), null, null))
            .andExpect(status().isForbidden());
    }

    @Test
    void attendanceStandsBesideTheWorkedTimeRecordedOnRunsDayByDay() throws Exception {
        String kim = member("editor");
        ZoneId zone = zone();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime in = now.minusHours(3);
        LocalDate attendDay = in.atZoneSameInstant(zone).toLocalDate();
        data(kim, post("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/attendance"), stretch(in, now.minusHours(1), "Shift"));
        String order = data(DEMO_OWNER, post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Attendance " + UUID.randomUUID(), "targetQuantity", 1)).path("workOrderId").asText();
        call(DEMO_OWNER, post("/work-orders/" + order + "/approve"), null).andExpect(status().isOk());
        String run = data(DEMO_OWNER, post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", order, "plannedOutputQty", 1)).path("productionRunId").asText();
        data(DEMO_OWNER, post("/production-runs/" + run + "/labor"), Map.of("requestId", UUID.randomUUID().toString(),
            "userId", kim, "workedMinutes", 45));
        // Read after recording, so the date the labor counts on is the one it was saved on.
        LocalDate laborDay = OffsetDateTime.now().atZoneSameInstant(zone).toLocalDate();

        String compare = "/projects/" + DEMO_PROJECT + "/workers/" + kim + "/attendance-comparison";
        JsonNode both = data(kim, get(compare).param("from", attendDay.toString()).param("to", laborDay.toString()), null);
        assertEquals(120, both.path("attendedMinutes").asInt());
        assertEquals(45, both.path("laborMinutes").asInt());
        for (JsonNode day : both.path("days")) {
            assertEquals(day.path("attendedMinutes").asInt() - day.path("laborMinutes").asInt(), day.path("differenceMinutes").asInt());
        }
        assertEquals(45, dayOf(both, laborDay).path("laborMinutes").asInt());
        assertEquals(120, dayOf(both, attendDay).path("attendedMinutes").asInt());
        data(DEMO_OWNER, get(compare), null);
        call(member("viewer"), get(compare), null).andExpect(status().isForbidden());
        call(DEMO_OWNER, get(compare).param("from", "2041-09-10").param("to", "2041-09-01"), null).andExpect(status().isBadRequest());
        call(DEMO_OWNER, get(compare).param("from", "2041-01-01").param("to", "2041-09-01"), null).andExpect(status().isBadRequest());
    }

    private static int attended(JsonNode response, LocalDate date) {
        return dayOf(response, date).path("attendedMinutes").asInt();
    }

    private static JsonNode dayOf(JsonNode response, LocalDate date) {
        for (JsonNode day : response.path("days")) {
            if (day.path("date").asText().equals(date.toString())) return day;
        }
        throw new AssertionError("No " + date + " in " + response.path("days"));
    }

    private static Map<String, Object> stretch(OffsetDateTime in, OffsetDateTime out, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("clockIn", in.toString());
        if (out != null) body.put("clockOut", out.toString());
        if (note != null) body.put("note", note);
        return body;
    }

    private ZoneId zone() throws Exception {
        return ZoneId.of(data(DEMO_OWNER, get("/projects/" + DEMO_PROJECT + "/time-zone"), null).path("timeZone").asText());
    }

    /** A new member of the demo project. */
    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
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
