package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Scheduling metadata follows project read access and never resolves equipment from another project (F15). */
@AutoConfigureMockMvc
class ProductionStepHeldAccessRegressionIntegrationTest extends IntegrationTestSupport {
    private static final OffsetDateTime READY = OffsetDateTime.parse("2041-09-09T08:00:00+09:00");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"owner", "viewer"})
    void anEventCannotResolveEquipmentCodeFromAnotherProject(String role) throws Exception {
        HeldRun run = new HeldRun();
        Line otherProject = new Line();
        String otherEquipment = otherProject.equipment();
        String privateCode = jdbc.queryForObject("select equipment_code from equipment where equipment_id=?", String.class, otherEquipment);
        String actor = "owner".equals(role) ? DEMO_OWNER : run.line.member("viewer");
        run.recordHeld(otherEquipment);
        var before = run.stored();

        JsonNode held = findNode(dataAs(actor, get(run.path()), null), run.line.mix).path("held");

        assertEquals(otherEquipment, held.path("equipmentId").asText());
        assertTrue(held.path("equipmentCode").isNull(), "Even the owner of both projects may only resolve this run's equipment");
        assertFalse(held.toString().contains(privateCode));
        assertEquals(READY.toInstant(), OffsetDateTime.parse(held.path("readyAt").asText()).toInstant());
        assertEquals(before, run.stored());
    }

    @Test
    void aViewerCanReadTheEquipmentCodeCrewAndReadyTimeOfTheirOwnProject() throws Exception {
        HeldRun run = new HeldRun();
        String viewer = run.line.member("viewer");
        var before = run.stored();
        JsonNode ownerView = data(get(run.path()), null);

        JsonNode viewerView = dataAs(viewer, get(run.path()), null);

        assertEquals(ownerView, viewerView);
        JsonNode held = findNode(viewerView, run.line.mix).path("held");
        assertEquals(run.equipment, held.path("equipmentId").asText());
        assertEquals(run.code, held.path("equipmentCode").asText());
        assertEquals(DEMO_OWNER, held.path("crew").get(0).asText());
        assertEquals(READY.toInstant(), OffsetDateTime.parse(held.path("readyAt").asText()).toInstant());
        assertEquals(before, run.stored());
    }

    @ParameterizedTest
    @CsvSource({"anonymous,401", "outsider,403"})
    void unauthorizedReadsExposeNoHoldMetadataAndDoNotChangeTheRun(String role, int expected) throws Exception {
        HeldRun run = new HeldRun();
        var before = run.stored();
        var request = get(run.path());
        if (!"anonymous".equals(role)) request.header("Authorization", "Bearer " + jwt.generateAccessToken(run.line.member("outsider")));

        var response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();

        assertFalse(response.getContentAsString().contains(run.code));
        assertFalse(response.getContentAsString().contains("readyAt"));
        assertEquals(before, run.stored());
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor"})
    void removingMembershipRevokesHoldMetadataWithTheSameToken(String role) throws Exception {
        HeldRun run = new HeldRun();
        String actor = run.line.member(role);
        String bearer = "Bearer " + jwt.generateAccessToken(actor);
        mvc.perform(get(run.path()).header("Authorization", bearer)).andExpect(status().isOk());
        jdbc.update("update project_member set member_status='removed' where project_id=? and user_id=?", run.line.project, actor);
        var before = run.stored();

        var response = mvc.perform(get(run.path()).header("Authorization", bearer)).andExpect(status().isForbidden()).andReturn().getResponse();

        assertFalse(response.getContentAsString().contains(run.code));
        assertFalse(response.getContentAsString().contains("readyAt"));
        assertEquals(before, run.stored());
    }

    @Test
    void aNewScheduleWithoutAHoldDoesNotReuseOlderEquipmentAndCrew() throws Exception {
        HeldRun run = new HeldRun();
        run.record(mapper.createObjectNode().put("connectionId", "no-longer-held"));
        var before = run.stored();

        JsonNode step = findNode(data(get(run.path()), null), run.line.mix);

        assertTrue(step.path("held").isNull());
        assertFalse(step.toString().contains(run.code));
        assertEquals(before, run.stored());
    }

    @Test
    void anotherRunsHoldCannotReplaceThisRunsMetadata() throws Exception {
        HeldRun first = new HeldRun();
        HeldRun second = new HeldRun();
        var before = first.stored();

        JsonNode step = findNode(data(get(first.path()), null), first.line.mix);

        assertEquals(first.equipment, step.path("held").path("equipmentId").asText());
        assertEquals(first.code, step.path("held").path("equipmentCode").asText());
        assertFalse(step.toString().contains(second.code));
        assertEquals(before, first.stored());
    }

    /** Historical system scheduling events let these read tests avoid a wall-clock race or a real production BOM. */
    private final class HeldRun {
        final Line line = new Line();
        final String equipment = line.equipment();
        final String code = jdbc.queryForObject("select equipment_code from equipment where equipment_id=?", String.class, equipment);
        final String production = line.start(line.order(equipment));
        final String flowRun = jdbc.queryForObject("select flow_run_id from flow_run where production_run_id=?", String.class, production);
        final String step = findNode(line.steps(production), line.mix).path("stepId").asText();
        int sequence;

        HeldRun() throws Exception {
            jdbc.update("update flow_run_step set scheduled_at=?::timestamptz where step_id=?", READY.plusHours(1).toString(), step);
            recordHeld(equipment);
        }

        String path() { return "/production-runs/" + production + "/steps"; }

        void recordHeld(String equipmentId) throws Exception {
            var payload = mapper.createObjectNode().put("readyAt", READY.toString()).put("equipmentId", equipmentId);
            payload.putArray("crew").add(DEMO_OWNER);
            record(payload);
        }

        void record(JsonNode payload) throws Exception {
            jdbc.update("insert into flow_run_event(event_id,flow_run_id,step_id,event_type,payload_json,occurred_at,actor_type,actor_id) "
                + "values(?,?,?,'step_scheduled',?::jsonb,?::timestamptz,'system','system')", UUID.randomUUID().toString(), flowRun, step,
                mapper.writeValueAsString(payload), READY.plusSeconds(++sequence).toString());
        }

        Map<String, List<Map<String, Object>>> stored() {
            return Map.of("run", jdbc.queryForList("select * from production_run where production_run_id=?", production),
                "steps", jdbc.queryForList("select * from flow_run_step where flow_run_id=? order by step_id", flowRun),
                "attempts", jdbc.queryForList("select a.* from flow_run_step_attempt a join flow_run_step s on a.step_id=s.step_id "
                    + "where s.flow_run_id=? order by a.attempt_id", flowRun),
                "events", jdbc.queryForList("select * from flow_run_event where flow_run_id=? order by event_id", flowRun));
        }
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

        String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        String equipment() throws Exception {
            return data(post("/equipments"), Map.of("projectId", project, "equipmentCode", "EQ-" + UUID.randomUUID(),
                "equipmentName", "Working time press", "equipmentType", "machine", "details", Map.of("capacityPerHour", 10)))
                .path("equipmentId").asText();
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

        String start(String order) throws Exception {
            JsonNode run = data(post("/production-runs/start"), Map.of("projectId", project, "workflowId", workflow,
                "workflowRevisionId", revision, "workOrderId", order, "plannedOutputQty", 10, "runType", "simulation"));
            assertTrue(run.path("bomId").isNull());
            return run.path("productionRunId").asText();
        }

        JsonNode steps(String run) throws Exception { return data(get("/production-runs/" + run + "/steps"), null); }

    }

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
