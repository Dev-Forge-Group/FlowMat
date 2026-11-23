package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The try-it schedule's two halves meet the server (docs/architecture/adr/ADR-041-try-it-time.md decisions 4 and 5): a
 * work order's stretches from {@code GET /work-orders/{id}/working-time}, put through {@link FlowRunTiming}, give the
 * times the router records for a production run of that order. The step before ends now; the crew works one hour a day,
 * three hours from now.
 */
@AutoConfigureMockMvc
class TryItTimeIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theStretchesTheApiGivesAreTheOnesAStepWaitsFor() throws Exception {
        Line line = new Line(0.01, "elapsed");
        String press = equipment();
        data(put("/equipments/" + press + "/calendar"), Map.of("shiftStart", "00:00", "shiftEnd", "00:00", "workDays", EVERY_DAY));
        ZonedDateTime opens = opensIn(3);
        String lee = member();
        shift(lee, opens, opens.plusHours(1));
        String order = line.order(press);
        crew(order, Map.of("userId", lee, "sharePercent", 100));
        String run = line.start(order);

        OffsetDateTime ended = completeMix(run, line);
        JsonNode time = data(get("/work-orders/" + order + "/working-time").param("from", ended.toString()), null);
        assertFalse(time.path("allTime").asBoolean());
        assertEquals(press, time.path("equipmentId").asText());
        assertTrue(time.path("equipmentCode").asText().startsWith("TRY-"));
        assertEquals(List.of(lee), texts(time.path("crew")));
        assertEquals(opens.toInstant(), OffsetDateTime.parse(time.path("stretches").get(0).get(0).asText()).toInstant());
        assertEquals(7, time.path("stretches").size(), "one hour a day for the default seven days");

        FlowRunTiming.WorkingTime team = FlowRunTiming.stretches(stretches(time), press, List.of(lee));
        OffsetDateTime readyAt = FlowRunTiming.readyAt(ended, null, Duration.ofMillis(10), false, team);
        FlowRunTiming.Scheduled expected = FlowRunTiming.scheduled(readyAt, false, team);
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(expected.at().toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        JsonNode scheduled = scheduledEvent(line.flowRunOf(run), bake.path("stepId").asText());
        assertEquals(readyAt.toInstant(), OffsetDateTime.parse(scheduled.path("readyAt").asText()).toInstant());
        assertEquals(expected.held().equipmentId(), scheduled.path("equipmentId").asText());
        assertEquals(expected.held().crew(), texts(scheduled.path("crew")));
    }

    @Test
    void aWorkingTimeDelayEndsWhereTheStretchesSay() throws Exception {
        // Half an hour of the crew's working time: from now, that is half an hour into kim's shift.
        Line line = new Line(1800, "working");
        ZonedDateTime opens = opensIn(3);
        String kim = member();
        shift(kim, opens, opens.plusHours(1));
        String order = line.order(null);
        crew(order, Map.of("userId", kim, "sharePercent", 100));
        String run = line.start(order);

        OffsetDateTime ended = completeMix(run, line);
        JsonNode time = data(get("/work-orders/" + order + "/working-time").param("from", ended.toString()), null);
        assertTrue(time.path("equipmentId").isNull());
        assertTrue(time.path("equipmentCode").isNull());
        assertEquals(List.of(kim), texts(time.path("crew")));

        FlowRunTiming.WorkingTime team = FlowRunTiming.stretches(stretches(time), null, List.of(kim));
        OffsetDateTime readyAt = FlowRunTiming.readyAt(ended, null, Duration.ofMinutes(30), true, team);
        assertEquals(opens.plusMinutes(30).toInstant(), readyAt.toInstant());
        FlowRunTiming.Scheduled expected = FlowRunTiming.scheduled(readyAt, false, team);
        assertNull(expected.held(), "ready inside kim's shift");
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(expected.at().toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        assertEquals("working", scheduledEvent(line.flowRunOf(run), bake.path("stepId").asText()).path("delayBasis").asText());
    }

    @Test
    void aWorkOrderWithNothingToFollowCountsAllTime() throws Exception {
        Line line = new Line(0.01, "elapsed");
        String order = line.order(null);
        String from = "2030-01-07T09:00:00+09:00";
        JsonNode time = data(get("/work-orders/" + order + "/working-time").param("from", from), null);
        assertTrue(time.path("allTime").asBoolean());
        assertTrue(time.path("equipmentId").isNull());
        assertEquals(0, time.path("crew").size());
        assertEquals(0, time.path("stretches").size());
        assertEquals(from, time.path("from").asText());
        assertEquals("2030-01-14T09:00:00+09:00", time.path("until").asText());
    }

    @Test
    void theWindowIsChecked() throws Exception {
        String order = new Line(0.01, "elapsed").order(null);
        String path = "/work-orders/" + order + "/working-time";
        call(get(path), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("from is required."));
        call(get(path).param("from", "tomorrow"), null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("from must be a date and time with an offset, such as 2030-01-07T09:00:00+09:00."));
        call(get(path).param("from", "2030-01-07T09:00:00+09:00").param("until", "2030-01-07T09:00:00+09:00"), null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("until must be after from."));
        call(get(path).param("from", "2030-01-07T09:00:00+09:00").param("until", "2031-01-09T09:00:00+09:00"), null)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("The window can be at most 366 days long."));
        call(get("/work-orders/" + UUID.randomUUID() + "/working-time").param("from", "2030-01-07T09:00:00+09:00"), null)
            .andExpect(status().isNotFound());
    }

    private ZonedDateTime opensIn(int hours) throws Exception {
        ZoneId zone = ZoneId.of(data(get("/projects/" + DEMO_PROJECT + "/time-zone"), null).path("timeZone").asText());
        return ZonedDateTime.now(zone).plusHours(hours).truncatedTo(ChronoUnit.HOURS);
    }

    /** Starts and completes Mix and returns when it ended. */
    private OffsetDateTime completeMix(String run, Line line) throws Exception {
        String mix = forNode(steps(run), line.mix).path("stepId").asText();
        data(post(step(run, mix, "start")), null);
        return OffsetDateTime.parse(data(post(step(run, mix, "complete")), Map.of()).path("endedAt").asText());
    }

    private static List<WorkingStretch> stretches(JsonNode time) {
        List<WorkingStretch> stretches = new ArrayList<>();
        time.path("stretches").forEach(pair -> stretches.add(new WorkingStretch(OffsetDateTime.parse(pair.get(0).asText()).toInstant(),
            OffsetDateTime.parse(pair.get(1).asText()).toInstant())));
        return stretches;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    /** Mix → Bake with the given delay, published. */
    private final class Line {
        final String workflow;
        final String mix;
        final String bake;
        final String revision;

        Line(double delaySeconds, String delayBasis) throws Exception {
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Try it " + UUID.randomUUID()))
                .path("workflowId").asText();
            mix = node("Mix");
            bake = node("Bake");
            data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake,
                "delayTimeSec", delaySeconds, "delayBasis", delayBasis));
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        private String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String order(String equipmentId) throws Exception {
            String order = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
                "workOrderTitle", "Try it " + UUID.randomUUID(), "targetQuantity", 10,
                "plannedStartAt", "2030-01-07T09:00:00+09:00", "plannedEndAt", "2030-01-07T17:00:00+09:00")).path("workOrderId").asText();
            if (equipmentId != null) {
                data(put("/work-orders/" + order + "/equipment"), Map.of("equipmentId", equipmentId));
            }
            data(post("/work-orders/" + order + "/approve"), null);
            return order;
        }

        String start(String workOrderId) throws Exception {
            return data(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
                "workflowRevisionId", revision, "workOrderId", workOrderId, "plannedOutputQty", 10, "runType", "simulation"))
                .path("productionRunId").asText();
        }

        String flowRunOf(String productionRun) throws Exception {
            for (JsonNode one : data(get("/flow-runs").param("workflowId", workflow), null)) {
                if (productionRun.equals(one.path("productionRunId").asText())) {
                    return one.path("flowRunId").asText();
                }
            }
            throw new AssertionError("No flow run for " + productionRun);
        }
    }

    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    private void shift(String userId, ZonedDateTime start, ZonedDateTime end) throws Exception {
        data(put("/projects/" + DEMO_PROJECT + "/workers/" + userId + "/shifts"), Map.of("shifts",
            List.of(Map.of("shiftStart", String.format("%02d:00", start.getHour()), "shiftEnd", String.format("%02d:00", end.getHour()),
                "workDays", EVERY_DAY))));
    }

    @SafeVarargs
    private void crew(String workOrderId, Map<String, Object>... members) throws Exception {
        data(put("/work-orders/" + workOrderId + "/crew"), Map.of("members", List.of(members)));
    }

    private String equipment() throws Exception {
        String code = "TRY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return data(post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentCode", code, "equipmentName", code.toLowerCase(),
            "equipmentType", "machine", "details", Map.of("capacityPerHour", 10))).path("equipmentId").asText();
    }

    private static String step(String run, String stepId, String action) {
        return "/production-runs/" + run + "/steps/" + stepId + "/" + action;
    }

    private JsonNode steps(String run) throws Exception {
        return data(get("/production-runs/" + run + "/steps"), null);
    }

    private static JsonNode forNode(JsonNode steps, String nodeId) {
        List<JsonNode> found = new ArrayList<>();
        steps.forEach(one -> {
            if (nodeId.equals(one.path("nodeId").asText())) found.add(one);
        });
        assertEquals(1, found.size(), steps.toString());
        return found.get(0);
    }

    private JsonNode scheduledEvent(String flowRun, String stepId) throws Exception {
        JsonNode found = null;
        for (JsonNode event : data(get("/flow-runs/" + flowRun + "/events"), null)) {
            if ("step_scheduled".equals(event.path("eventType").asText()) && stepId.equals(event.path("stepId").asText())) {
                found = event.path("payload");
            }
        }
        if (found == null) {
            throw new AssertionError("No step_scheduled event for " + stepId);
        }
        return found;
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(request, body).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
