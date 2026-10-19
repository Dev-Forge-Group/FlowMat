package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** ADR-022 working delays across crew, calendar versions, gateways and old revision shapes. */
@AutoConfigureMockMvc
class WorkingDelayCalendarRegressionIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> DAYS = List.of(1, 2, 3, 4, 5, 6, 7);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void workingDelayUsesOnlyTheFullCrewAndEquipmentOverlap() throws Exception {
        Line line = new Line();
        String from = line.node("Mix", "process");
        String to = line.node("Bake", "process");
        line.link(from, to, Map.of("delayTimeSec", 2700, "delayBasis", "working"));
        data(put("/projects/" + line.project + "/workers/" + DEMO_OWNER + "/shifts"), Map.of("shifts", List.of(Map.of(
            "shiftStart", time(line.opens.plusMinutes(30)), "shiftEnd", time(line.opens.plusMinutes(90)), "workDays", DAYS))));
        String run = line.start(true);
        line.complete(run, from);
        // Each day shares only the last 30 minutes of the equipment's hour with the worker.
        line.assertWorkingSchedule(run, to, line.opens.plusDays(1).plusMinutes(45));
    }

    @Test
    void workingDelayContinuesInTheEquipmentVersionThatStartsTomorrow() throws Exception {
        Line line = new Line();
        String from = line.node("Mix", "process");
        String to = line.node("Bake", "process");
        line.link(from, to, Map.of("delayTimeSec", 5400, "delayBasis", "working"));
        line.calendar(line.opens.plusHours(2), line.opens.plusHours(3), line.opens.toLocalDate().plusDays(1).toString());
        String run = line.start(false);
        line.complete(run, from);
        // One hour in the current version, then 30 minutes in tomorrow's later shift.
        line.assertWorkingSchedule(run, to, line.opens.plusDays(1).plusHours(2).plusMinutes(30));
    }

    @Test
    void workingDelaysOnBothSplitBranchesWaitForWorkingTime() throws Exception {
        Line line = new Line();
        String from = line.node("Mix", "process");
        String split = line.node("Split", "split");
        String left = line.node("Left", "process");
        String right = line.node("Right", "process");
        line.link(from, split, Map.of());
        for (String to : List.of(left, right)) line.link(split, to, Map.of("splitKind", "share", "splitValue", 50,
            "delayTimeSec", 1800, "delayBasis", "working"));
        String run = line.start(false);
        line.complete(run, from);
        for (String to : List.of(left, right)) line.assertWorkingSchedule(run, to, line.opens.plusMinutes(30));
    }

    @Test
    void aJoinAppliesItsOutgoingWorkingDelayAfterBothRootsComplete() throws Exception {
        Line line = new Line();
        String left = line.node("Left", "process");
        String right = line.node("Right", "process");
        String join = line.node("Join", "join");
        String end = line.node("Pack", "process");
        line.link(left, join, Map.of());
        line.link(right, join, Map.of());
        line.link(join, end, Map.of("delayTimeSec", 1800, "delayBasis", "working"));
        String run = line.start(false);
        line.complete(run, left);
        for (JsonNode step : line.steps(run)) assertFalse(end.equals(step.path("nodeId").asText()));
        line.complete(run, right);
        line.assertWorkingSchedule(run, end, line.opens.plusMinutes(30));
    }

    @Test
    void aPreV99SnapshotWithoutDelayBasisStillCountsElapsedTime() throws Exception {
        Line line = new Line();
        String from = line.node("Mix", "process");
        String to = line.node("Bake", "process");
        line.link(from, to, Map.of("delayTimeSec", 1800, "delayBasis", "working"));
        line.publish();
        // A legacy persisted JSON shape, prepared only in this Testcontainers database.
        jdbc.update("update workflow_revision set snapshot_json=jsonb_set(snapshot_json,'{connections,0}',"
            + "(snapshot_json->'connections'->0)-'delayBasis') where workflow_revision_id=?", line.revision);
        assertFalse(data(get("/workflows/" + line.workflow + "/revisions/" + line.revision), null)
            .path("snapshot").path("connections").get(0).has("delayBasis"));
        String run = line.start(false);
        line.complete(run, from);
        // The elapsed delay ends before the shift; working-start alignment waits only to its opening, not 30 minutes into it.
        JsonNode step = line.step(run, to);
        assertEquals(line.opens.toInstant(), OffsetDateTime.parse(step.path("scheduledAt").asText()).toInstant());
        assertFalse(line.scheduledPayload(run, step.path("stepId").asText()).has("delayBasis"));
    }

    private final class Line {
        final String project;
        final String workflow;
        final String equipment;
        final ZonedDateTime opens;
        String revision;

        Line() throws Exception {
            project = data(post("/projects"), Map.of("projectName", "Working delay " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
                .path("projectId").asText();
            workflow = data(post("/workflows"), Map.of("projectId", project, "workflowName", "Delay calendar"))
                .path("workflowId").asText();
            equipment = data(post("/equipments"), Map.of("projectId", project, "equipmentCode", "D-" + UUID.randomUUID(),
                "equipmentName", "Delay press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))
                .path("equipmentId").asText();
            ZoneId zone = ZoneId.of(data(get("/projects/" + project + "/time-zone"), null).path("timeZone").asText());
            opens = ZonedDateTime.now(zone).plusHours(4).truncatedTo(ChronoUnit.HOURS);
            calendar(opens, opens.plusHours(1), null);
        }

        void calendar(ZonedDateTime start, ZonedDateTime end, String effective) throws Exception {
            Map<String, Object> body = new HashMap<>(Map.of("shiftStart", time(start), "shiftEnd", time(end), "workDays", DAYS));
            if (effective != null) body.put("effectiveFrom", effective);
            data(put("/equipments/" + equipment + "/calendar"), body);
        }

        String node(String name, String type) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", type))
                .path("processId").asText();
        }

        void link(String from, String to, Map<String, ?> options) throws Exception {
            Map<String, Object> body = new HashMap<>(options);
            body.putAll(Map.of("workflowId", workflow, "fromProcessId", from, "toProcessId", to));
            data(post("/process-connections"), body);
        }

        void publish() throws Exception {
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        String start(boolean withCrew) throws Exception {
            if (revision == null) publish();
            String order = data(post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
                "workOrderTitle", "Delay order", "targetQuantity", 10,
                "plannedStartAt", "2041-09-09T09:00:00+09:00", "plannedEndAt", "2041-09-09T17:00:00+09:00"))
                .path("workOrderId").asText();
            data(put("/work-orders/" + order + "/equipment"), Map.of("equipmentId", equipment));
            if (withCrew) data(put("/work-orders/" + order + "/crew"), Map.of("members",
                List.of(Map.of("userId", DEMO_OWNER, "sharePercent", 100))));
            data(post("/work-orders/" + order + "/approve"), null);
            JsonNode run = data(post("/production-runs/start"), Map.of("projectId", project, "workflowId", workflow,
                "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 10, "runType", "simulation"));
            assertTrue(run.path("bomId").isNull());
            return run.path("productionRunId").asText();
        }

        JsonNode steps(String run) throws Exception { return data(get("/production-runs/" + run + "/steps"), null); }

        JsonNode step(String run, String node) throws Exception {
            for (JsonNode step : steps(run)) if (node.equals(step.path("nodeId").asText())) return step;
            throw new AssertionError("Missing node " + node);
        }

        void complete(String run, String node) throws Exception {
            String path = "/production-runs/" + run + "/steps/" + step(run, node).path("stepId").asText();
            data(post(path + "/start"), null);
            data(post(path + "/complete"), Map.of("outputSnapshot", Map.of("quantity", 100, "unit", "kg")));
        }

        void assertWorkingSchedule(String run, String node, ZonedDateTime expected) throws Exception {
            JsonNode step = step(run, node);
            assertEquals(expected.toInstant(), OffsetDateTime.parse(step.path("scheduledAt").asText()).toInstant());
            assertEquals("working", scheduledPayload(run, step.path("stepId").asText()).path("delayBasis").asText());
        }

        JsonNode scheduledPayload(String run, String step) throws Exception {
            String flowRun = null;
            for (JsonNode candidate : data(get("/flow-runs").param("workflowId", workflow), null)) {
                if (run.equals(candidate.path("productionRunId").asText())) flowRun = candidate.path("flowRunId").asText();
            }
            if (flowRun == null) throw new AssertionError("No linked flow run for " + run);
            for (JsonNode event : data(get("/flow-runs/" + flowRun + "/events"), null)) {
                if ("step_scheduled".equals(event.path("eventType").asText()) && step.equals(event.path("stepId").asText())) {
                    return event.path("payload");
                }
            }
            throw new AssertionError("No scheduling event for " + step);
        }
    }

    private static String time(ZonedDateTime time) { return TIME.format(time); }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), parsed.path("message").asText());
        return parsed.path("data");
    }
}
