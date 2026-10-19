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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Calendar versions and early starts must preserve the production and project access contracts. */
@AutoConfigureMockMvc
class LinkedProductionWorkingTimeRegressionIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> DAYS = List.of(1, 2, 3, 4, 5, 6, 7);
    private static final OffsetDateTime BEFORE = OffsetDateTime.parse("2041-09-09T08:00:00+09:00");
    private static final OffsetDateTime AFTER = OffsetDateTime.parse("2041-09-10T08:00:00+09:00");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LinkedRunWorkingTime working;

    @Test
    void removingTheEquipmentCalendarInTheFutureKeepsEarlierWorkingTime() throws Exception {
        Line line = new Line();
        String equipment = line.equipment();
        line.equipmentShift(equipment, "09:00", "17:00");
        data(delete("/equipments/" + equipment + "/calendar").param("effectiveFrom", "2041-09-10"), null);
        String run = line.start(line.order(equipment));
        assertEquals(BEFORE.plusHours(1).toInstant(), working.workingStart(run, BEFORE).orElseThrow().at().toInstant());
        assertTrue(working.workingStart(run, AFTER).isEmpty(), "Calendar removal must make only the later date always available");
    }

    @Test
    void aWorkersFutureVersionChangesOnlyThatDatesStart() throws Exception {
        Line line = new Line();
        String worker = line.member("editor");
        line.workerShift(worker, "09:00", "17:00", null);
        line.workerShift(worker, "14:00", "20:00", "2041-09-10");
        String order = line.order(null);
        line.crew(order, worker, 100);
        String run = line.start(order);
        assertEquals(BEFORE.plusHours(1).toInstant(), working.workingStart(run, BEFORE).orElseThrow().at().toInstant());
        var future = working.workingStart(run, AFTER).orElseThrow();
        assertEquals(AFTER.plusHours(6).toInstant(), future.at().toInstant());
        assertEquals(List.of(worker), future.crew());
        assertEquals(null, future.equipmentId());
    }

    @Test
    void anAlwaysAvailableEquipmentVersionStillWaitsForItsFullShareCrew() throws Exception {
        Line line = new Line();
        String equipment = line.equipment();
        line.equipmentShift(equipment, "09:00", "17:00");
        data(delete("/equipments/" + equipment + "/calendar").param("effectiveFrom", "2041-09-10"), null);
        String worker = line.member("editor");
        line.workerShift(worker, "14:00", "20:00", null);
        String order = line.order(equipment);
        line.crew(order, worker, 100);
        String run = line.start(order);
        assertEquals(AFTER.plusHours(6).toInstant(), working.workingStart(run, AFTER).orElseThrow().at().toInstant());
    }

    @Test
    void partShareOrUnknownShiftsDoNotConstrainAnOtherwiseAvailableStart() throws Exception {
        Line line = new Line();
        String equipment = line.equipment();
        line.equipmentShift(equipment, "00:00", "00:00");
        String part = line.member("editor");
        String unknown = line.member("editor");
        line.workerShift(part, "14:00", "15:00", null);
        String order = line.order(equipment);
        data(put("/work-orders/" + order + "/crew"), Map.of("members", List.of(
            Map.of("userId", part, "sharePercent", 50), Map.of("userId", unknown, "sharePercent", 100))));
        assertTrue(working.workingStart(line.start(order), BEFORE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "outsider"})
    void calendarAvailabilityCannotGrantEarlyStartPermission(String role) throws Exception {
        Line line = new Line();
        String worker = line.member("editor");
        String actor = line.member(role);
        ZonedDateTime opens = ZonedDateTime.now(line.zone()).plusHours(3).truncatedTo(ChronoUnit.HOURS);
        line.workerShift(worker, hour(opens), hour(opens.plusHours(1)), null);
        String order = line.order(null);
        line.crew(order, worker, 100);
        String run = line.start(order);
        JsonNode bake = line.finishMix(run);
        String start = "/production-runs/" + run + "/steps/" + bake.path("stepId").asText() + "/start";
        JsonNode before = line.steps(run);
        call(actor, post(start), null).andExpect(status().isForbidden());
        assertEquals(before, line.steps(run));
        data(put("/projects/" + line.project + "/workers/" + worker + "/days/" + ZonedDateTime.now(line.zone()).toLocalDate()),
            Map.of("shifts", List.of(shift("00:00", "00:00")), "reason", "Regression overtime"));
        call(actor, post(start), null).andExpect(status().isForbidden());
        assertEquals(before, line.steps(run));
        assertEquals("running", dataAs(worker, post(start), null).path("status").asText());
    }

    @Test
    void overtimeCannotBypassTheConnectionDelayItself() throws Exception {
        Line line = new Line(3600);
        String worker = line.member("editor");
        line.workerShift(worker, "00:00", "00:00", null);
        String order = line.order(null);
        line.crew(order, worker, 100);
        String run = line.start(order);
        JsonNode bake = line.finishMix(run);
        JsonNode before = line.steps(run);
        call(worker, post("/production-runs/" + run + "/steps/" + bake.path("stepId").asText() + "/start"), null)
            .andExpect(status().isConflict());
        assertEquals(before, line.steps(run));
        assertFalse(bake.path("scheduledAt").isNull());
    }

    private final class Line {
        final String project;
        final String workflow;
        final String mix;
        final String bake;
        final String revision;

        Line() throws Exception { this(0.01); }

        Line(double delay) throws Exception {
            project = data(post("/projects"), Map.of("projectName", "Working time " + UUID.randomUUID(), "ownerId", DEMO_OWNER))
                .path("projectId").asText();
            workflow = data(post("/workflows"), Map.of("projectId", project, "workflowName", "Mix to bake"))
                .path("workflowId").asText();
            mix = node("Mix");
            bake = node("Bake");
            data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake,
                "delayTimeSec", delay));
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        ZoneId zone() throws Exception { return ZoneId.of(data(get("/projects/" + project + "/time-zone"), null).path("timeZone").asText()); }

        String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String equipment() throws Exception {
            return data(post("/equipments"), Map.of("projectId", project, "equipmentCode", "EQ-" + UUID.randomUUID(),
                "equipmentName", "Working time press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))
                .path("equipmentId").asText();
        }

        void equipmentShift(String id, String from, String to) throws Exception {
            data(put("/equipments/" + id + "/calendar"), Map.of("shiftStart", from, "shiftEnd", to, "workDays", DAYS));
        }

        void workerShift(String id, String from, String to, String effective) throws Exception {
            var body = mapper.valueToTree(Map.of("shifts", List.of(shift(from, to))));
            if (effective != null) ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("effectiveFrom", effective);
            data(put("/projects/" + project + "/workers/" + id + "/shifts"), body);
        }

        String member(String role) {
            String id = "working-" + UUID.randomUUID();
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
                + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
            if (!"outsider".equals(role)) jdbc.update(
                "insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
                UUID.randomUUID().toString(), project, id, role);
            return id;
        }

        String order(String equipment) throws Exception {
            String id = data(post("/work-orders"), Map.of("projectId", project, "workflowId", workflow,
                "workOrderTitle", "Working time", "targetQuantity", 10,
                "plannedStartAt", "2041-09-09T09:00:00+09:00", "plannedEndAt", "2041-09-09T17:00:00+09:00"))
                .path("workOrderId").asText();
            if (equipment != null) data(put("/work-orders/" + id + "/equipment"), Map.of("equipmentId", equipment));
            data(post("/work-orders/" + id + "/approve"), null);
            return id;
        }

        void crew(String order, String worker, int share) throws Exception {
            data(put("/work-orders/" + order + "/crew"), Map.of("members", List.of(Map.of("userId", worker, "sharePercent", share))));
        }

        String start(String order) throws Exception {
            JsonNode run = data(post("/production-runs/start"), Map.of("projectId", project, "workflowId", workflow,
                "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 10, "runType", "simulation"));
            assertTrue(run.path("bomId").isNull());
            return run.path("productionRunId").asText();
        }

        JsonNode steps(String run) throws Exception { return data(get("/production-runs/" + run + "/steps"), null); }

        JsonNode finishMix(String run) throws Exception {
            JsonNode first = findNode(steps(run), mix);
            String base = "/production-runs/" + run + "/steps/" + first.path("stepId").asText();
            data(post(base + "/start"), null);
            data(post(base + "/complete"), Map.of());
            return findNode(steps(run), bake);
        }
    }

    private static Map<String, Object> shift(String start, String end) {
        return Map.of("shiftStart", start, "shiftEnd", end, "workDays", DAYS);
    }

    private static String hour(ZonedDateTime value) { return String.format("%02d:00", value.getHour()); }

    private static JsonNode findNode(JsonNode steps, String id) {
        for (JsonNode one : steps) if (id.equals(one.path("nodeId").asText())) return one;
        throw new AssertionError("Missing step for " + id);
    }

    private ResultActions call(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request.header("Authorization", "Bearer " + jwt.generateAccessToken(actor)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception { return dataAs(DEMO_OWNER, request, body); }

    private JsonNode dataAs(String actor, MockHttpServletRequestBuilder request, Object body) throws Exception {
        var response = call(actor, request, body).andReturn().getResponse();
        assertEquals(200, response.getStatus(), request.toString());
        return mapper.readTree(response.getContentAsString()).path("data");
    }
}
