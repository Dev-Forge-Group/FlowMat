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

/** Date-range partition invariants for attendance and pay across project time-zone changes, on disposable Postgres. */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AttendanceTimeZonePartitionRegressionIntegrationTest extends IntegrationTestSupport {
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "Asia/Seoul,UTC,2026-09-02T00:30:00+09:00",
        "UTC,Asia/Seoul,2026-09-01T18:30:00Z",
        "Asia/Seoul,Asia/Seoul,2026-09-02T00:30:00+09:00",
        "UTC,UTC,2026-09-01T18:30:00Z"
    })
    void dailyPartitionsMustAgreeWithCombinedPeriodAfterZoneChange(String initialZone, String newZone, String clockIn) throws Exception {
        setZone(initialZone);
        String worker = member();
        data(DEMO_OWNER, post("/projects/" + projectId + "/workers/" + worker + "/attendance"),
            Map.of("requestId", UUID.randomUUID().toString(), "clockIn", clockIn,
                "clockOut", java.time.OffsetDateTime.parse(clockIn).plusHours(1).toString()));
        setZone(newZone);
        JsonNode combined = attendance(worker, "2026-09-01", "2026-09-02");
        JsonNode first = attendance(worker, "2026-09-01", "2026-09-01");
        JsonNode second = attendance(worker, "2026-09-02", "2026-09-02");
        assertEquals(1, combined.path("lines").size());
        assertEquals(60, minutes(combined));
        int combinedPay = payMinutes(worker, "2026-09-01", "2026-09-02");
        int splitPay = payMinutes(worker, "2026-09-01", "2026-09-01") + payMinutes(worker, "2026-09-02", "2026-09-02");
        org.junit.jupiter.api.Assertions.assertAll(
            () -> assertEquals(60, combinedPay),
            () -> assertEquals(minutes(combined), minutes(first) + minutes(second), "Attendance daily partition"),
            () -> assertEquals(combinedPay, splitPay, "Attendance-pay daily partition"));
    }

    private void setZone(String zone) throws Exception {
        String path = "/projects/" + projectId + "/time-zone";
        JsonNode current = data(DEMO_OWNER, get(path), null);
        data(DEMO_OWNER, put(path), Map.of("timeZone", zone, "expectedVersion", current.path("version").asLong()));
    }

    private JsonNode attendance(String worker, String from, String to) throws Exception {
        return data(DEMO_OWNER, get("/projects/" + projectId + "/workers/" + worker + "/attendance")
            .param("from", from).param("to", to), null);
    }

    private int payMinutes(String worker, String from, String to) throws Exception {
        return data(DEMO_OWNER, get("/projects/" + projectId + "/workers/" + worker + "/attendance-pay")
            .param("from", from).param("to", to), null).path("attendedMinutes").asInt();
    }

    private static int minutes(JsonNode response) {
        int total = 0;
        for (JsonNode day : response.path("days")) total += day.path("attendedMinutes").asInt();
        return total;
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
