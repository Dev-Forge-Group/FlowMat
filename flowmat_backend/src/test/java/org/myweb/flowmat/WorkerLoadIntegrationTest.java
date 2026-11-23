package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Workers' load and double-booked crew (docs/domain/worker-calendar.md WC17-WC23) against real Postgres. */
@AutoConfigureMockMvc
class WorkerLoadIntegrationTest extends IntegrationTestSupport {

    // 2041-04-01 is a Monday; the demo project plans in Asia/Seoul (+09:00).
    private static final String MONDAY = "2041-04-01T00:00:00+09:00";
    private static final String NEXT_MONDAY = "2041-04-08T00:00:00+09:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void eachWorkerSeesTheirBookedHoursAndReadinessFlagsTheSameTimeElsewhere() throws Exception {
        String kim = member("editor");
        String lee = member("editor");
        shifts(kim, List.of(Map.of("shiftStart", "09:00", "shiftEnd", "17:00", "workDays", List.of(1, 2, 3, 4, 5))));
        String first = order("First", at(1, 9), at(2, 17));
        String second = order("Second", at(2, 9), at(3, 17));
        String draft = order("Draft", at(4, 9), at(4, 17));
        String cancelled = order("Cancelled", at(5, 9), at(5, 17));
        String undated = order("Undated", null, null);
        for (String id : List.of(first, second, cancelled)) {
            callAs(DEMO_OWNER, post("/work-orders/" + id + "/approve"), null).andExpect(status().isOk());
        }
        crew(first, kim, lee);
        crew(second, kim, lee);
        crew(draft, kim);
        crew(cancelled, kim);
        crew(undated, kim);
        callAs(DEMO_OWNER, post("/work-orders/" + cancelled + "/cancel"), "{}").andExpect(status().isOk());
        String firstNumber = number(first);
        String secondNumber = number(second);

        // Kim works 40 h; the first and second orders take 16 h each, the draft 8 h apart, and the cancelled one nothing.
        JsonNode row = worker(load(MONDAY, NEXT_MONDAY), kim);
        assertTrue(row.path("calendarSet").asBoolean());
        assertEquals(40.0, row.path("shiftHours").asDouble());
        assertEquals(32.0, row.path("plannedHours").asDouble());
        assertEquals(8.0, row.path("draftHours").asDouble());
        assertEquals(80.0, row.path("loadPercent").asDouble());
        assertFalse(row.path("overloaded").asBoolean());
        assertEquals(1, row.path("unplannedOrders").asInt());
        assertEquals(List.of(firstNumber, secondNumber, number(draft)), numbers(row.path("orders")));
        assertEquals(16.0, row.path("orders").get(0).path("hoursInWindow").asDouble());
        // Both approved orders want kim on Tuesday 9 to 17.
        JsonNode overlap = row.path("overlaps").get(0);
        assertEquals(1, row.path("overlaps").size());
        assertEquals(firstNumber, overlap.path("firstWorkOrderNumber").asText());
        assertEquals(secondNumber, overlap.path("secondWorkOrderNumber").asText());
        assertEquals(OffsetDateTime.parse(at(2, 9)).toInstant(), OffsetDateTime.parse(overlap.path("from").asText()).toInstant());
        assertEquals(OffsetDateTime.parse(at(2, 17)).toInstant(), OffsetDateTime.parse(overlap.path("to").asText()).toInstant());
        assertEquals(8.0, overlap.path("hours").asDouble());
        // Lee has no shifts: no hours, but the same two orders at once are still listed.
        JsonNode leeRow = worker(load(MONDAY, NEXT_MONDAY), lee);
        assertFalse(leeRow.path("calendarSet").asBoolean());
        assertTrue(leeRow.path("loadPercent").isNull());
        assertEquals(1, leeRow.path("overlaps").size());
        assertEquals(0.0, leeRow.path("overlaps").get(0).path("hours").asDouble());

        // Readiness of the first order names who is booked elsewhere at the same time; the draft has nobody elsewhere.
        assertEquals("Also booked at the same time: " + kim + " on " + secondNumber + " for 8 h, " + lee + " on " + secondNumber + ".",
            check(first, "crew_schedule").path("message").asText());
        assertEquals("warn", check(first, "crew_schedule").path("status").asText());
        assertTrue(check(draft, "crew_schedule").isMissingNode());

        // A week-long order on top: kim is booked 72 of 40 hours.
        String week = order("Week", at(1, 9), at(5, 17));
        callAs(DEMO_OWNER, post("/work-orders/" + week + "/approve"), null).andExpect(status().isOk());
        crew(week, kim);
        row = worker(load(MONDAY, NEXT_MONDAY), kim);
        assertEquals(72.0, row.path("plannedHours").asDouble());
        assertEquals(180.0, row.path("loadPercent").asDouble());
        assertTrue(row.path("overloaded").asBoolean());
        assertEquals(3, row.path("overlaps").size());
        assertEquals("Also booked at the same time: " + kim + " on " + number(week) + " for 8 h.",
            check(draft, "crew_schedule").path("message").asText());

        callAs(DEMO_OWNER, get("/worker-load").param("from", MONDAY).param("to", NEXT_MONDAY), null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("projectId is required."));
        callAs(DEMO_OWNER, loadRequest(NEXT_MONDAY, MONDAY), null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("to must be after from."));
        callAs(DEMO_OWNER, loadRequest(MONDAY, "2041-07-03T00:00:00+09:00"), null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("The worker load board covers at most 92 days."));
        callAs(DEMO_OWNER, loadRequest("monday", NEXT_MONDAY), null).andExpect(status().isBadRequest());
        callAs("load-outsider", loadRequest(MONDAY, NEXT_MONDAY), null).andExpect(status().isForbidden());
        callAs(lee, loadRequest(MONDAY, NEXT_MONDAY), null).andExpect(status().isOk());

        for (String id : List.of(first, second, week)) {
            callAs(DEMO_OWNER, post("/work-orders/" + id + "/cancel"), "{}").andExpect(status().isOk());
        }
        shifts(kim, List.of());
    }

    private static String at(int day, int hour) {
        return String.format("2041-04-%02dT%02d:00:00+09:00", day, hour);
    }

    private String order(String title, String start, String end) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("workOrderTitle", title + " " + UUID.randomUUID().toString().substring(0, 8));
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetQuantity", 10);
        body.put("plannedStartAt", start);
        body.put("plannedEndAt", end);
        return data(callAs(DEMO_OWNER, post("/work-orders"), json(body))).path("workOrderId").asText();
    }

    private String number(String workOrderId) throws Exception {
        return data(callAs(DEMO_OWNER, get("/work-orders/" + workOrderId), null)).path("workOrderNumber").asText();
    }

    private void crew(String workOrderId, String... userIds) throws Exception {
        callAs(DEMO_OWNER, put("/work-orders/" + workOrderId + "/crew"), json(Map.of("userIds", List.of(userIds))))
            .andExpect(status().isOk());
    }

    private void shifts(String userId, List<Map<String, Object>> shifts) throws Exception {
        callAs(DEMO_OWNER, put("/projects/" + DEMO_PROJECT + "/workers/" + userId + "/shifts"), json(Map.of("shifts", shifts)))
            .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder loadRequest(String from, String to) {
        return get("/worker-load").param("projectId", DEMO_PROJECT).param("from", from).param("to", to);
    }

    private JsonNode load(String from, String to) throws Exception {
        return data(callAs(DEMO_OWNER, loadRequest(from, to), null));
    }

    private static JsonNode worker(JsonNode load, String userId) {
        for (JsonNode row : load.path("workers")) {
            if (userId.equals(row.path("userId").asText())) {
                return row;
            }
        }
        throw new AssertionError(userId + " is not on the board");
    }

    private static List<String> numbers(JsonNode orders) {
        List<String> numbers = new ArrayList<>();
        orders.forEach(order -> numbers.add(order.path("workOrderNumber").asText()));
        return numbers;
    }

    private JsonNode check(String workOrderId, String code) throws Exception {
        for (JsonNode check : data(callAs(DEMO_OWNER, get("/work-orders/" + workOrderId + "/readiness"), null)).path("checks")) {
            if (code.equals(check.path("code").asText())) {
                return check;
            }
        }
        return objectMapper.missingNode();
    }

    private String member(String role) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, role);
        return id;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
