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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

/**
 * A connection delay in a production run also waits for the work order's crew
 * (docs/architecture/adr/ADR-016-crew-aware-delay.md). The delay is a hundredth of a second, so it is over by the next
 * request; each crew member works one hour a day, two to three hours from now.
 */
@AutoConfigureMockMvc
class CrewAwareDelayIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void withoutEquipmentTheStepWaitsForTheCrewUnlessTheyWorkNow() throws Exception {
        Line line = new Line();
        ZoneId zone = ZoneId.of(data(get("/projects/" + DEMO_PROJECT + "/time-zone"), null).path("timeZone").asText());
        ZonedDateTime opens = ZonedDateTime.now(zone).plusHours(3).truncatedTo(ChronoUnit.HOURS);
        String kim = member();
        shift(kim, opens, opens.plusHours(1));
        String order = line.order(null);
        crew(order, Map.of("userId", kim, "sharePercent", 100));
        String run = line.start(order);

        OffsetDateTime ready = completeMix(run, line);
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(opens.toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        JsonNode scheduled = scheduledEvent(line.flowRunOf(run), bake.path("stepId").asText());
        assertEquals(ready.toInstant(), OffsetDateTime.parse(scheduled.path("readyAt").asText()).toInstant());
        assertEquals(kim, scheduled.path("crew").get(0).asText());
        assertFalse(scheduled.has("equipmentId"));
        // The production step says it too: when the delay ended and whom it waits for.
        assertEquals(ready.toInstant(), OffsetDateTime.parse(bake.path("held").path("readyAt").asText()).toInstant());
        assertEquals(kim, bake.path("held").path("crew").get(0).asText());
        assertTrue(bake.path("held").path("equipmentId").isNull());

        // Off shift it waits; kim's overtime today, given as that date's own shifts, lets it start now (decision 6).
        String baking = bake.path("stepId").asText();
        call(post(step(run, baking, "start")), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Step cannot start before its scheduledAt."));
        data(put("/projects/" + DEMO_PROJECT + "/workers/" + kim + "/days/" + ZonedDateTime.now(zone).toLocalDate()),
            Map.of("shifts", List.of(Map.of("shiftStart", "00:00", "shiftEnd", "00:00")), "reason", "Overtime"));
        assertEquals("running", data(post(step(run, baking, "start")), null).path("status").asText());
    }

    @Test
    void theEquipmentAndTheFullShareCrewWithShiftsSetTheTimeTogether() throws Exception {
        Line line = new Line();
        String press = equipment();
        ZoneId zone = ZoneId.of(data(get("/equipments/" + press + "/schedule"), null).path("timeZone").asText());
        // The press runs all day, so it alone would not wait.
        data(put("/equipments/" + press + "/calendar"), Map.of("shiftStart", "00:00", "shiftEnd", "00:00", "workDays", EVERY_DAY));
        ZonedDateTime opens = ZonedDateTime.now(zone).plusHours(3).truncatedTo(ChronoUnit.HOURS);
        String lee = member();
        String park = member();
        shift(lee, opens, opens.plusHours(1));
        // Park works only the hour after; at half share park does not fix the time (WC39).
        shift(park, opens.plusHours(1), opens.plusHours(2));
        String order = line.order(press);
        crew(order, Map.of("userId", lee, "sharePercent", 100), Map.of("userId", park, "sharePercent", 50));
        String run = line.start(order);

        completeMix(run, line);
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(opens.toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        JsonNode scheduled = scheduledEvent(line.flowRunOf(run), bake.path("stepId").asText());
        assertEquals(press, scheduled.path("equipmentId").asText());
        assertEquals(1, scheduled.path("crew").size());
        assertEquals(lee, scheduled.path("crew").get(0).asText());
        assertEquals(press, bake.path("held").path("equipmentId").asText());
        assertTrue(bake.path("held").path("equipmentCode").asText().startsWith("CREW-"));

        // Someone on the crew without shifts does not fix the time: the step keeps the delay's end.
        String choi = member();
        String unknown = line.order(null);
        crew(unknown, Map.of("userId", choi, "sharePercent", 100));
        String other = line.start(unknown);
        OffsetDateTime ready = completeMix(other, line);
        JsonNode next = forNode(steps(other), line.bake);
        assertEquals(ready.toInstant(), OffsetDateTime.parse(next.path("scheduledAt").asText()).toInstant());
        assertFalse(scheduledEvent(line.flowRunOf(other), next.path("stepId").asText()).has("readyAt"));
        assertTrue(next.path("held").isNull());
    }

    /** Starts and completes Mix and returns when its output is ready on Bake: the end plus the connection's delay. */
    private OffsetDateTime completeMix(String run, Line line) throws Exception {
        String mix = forNode(steps(run), line.mix).path("stepId").asText();
        data(post(step(run, mix, "start")), null);
        JsonNode mixed = data(post(step(run, mix, "complete")), Map.of());
        return OffsetDateTime.parse(mixed.path("endedAt").asText()).plusNanos(10_000_000).truncatedTo(ChronoUnit.MICROS);
    }

    /** Mix → Bake with a hundredth of a second's delay, published. */
    private final class Line {
        final String workflow;
        final String mix;
        final String bake;
        final String revision;

        Line() throws Exception {
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Crew " + UUID.randomUUID()))
                .path("workflowId").asText();
            mix = node("Mix");
            bake = node("Bake");
            data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake,
                "delayTimeSec", 0.01));
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        private String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        /** An approved work order on this workflow, on the equipment when one is given. */
        String order(String equipmentId) throws Exception {
            String order = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
                "workOrderTitle", "Crew " + UUID.randomUUID(), "targetQuantity", 10,
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

    /** A new editor of the demo project, with no shifts yet. */
    private String member() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) "
            + "select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?", id, id + "@test.local", DEMO_OWNER);
        jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",
            UUID.randomUUID().toString(), DEMO_PROJECT, id, "editor");
        return id;
    }

    /** One shift every day, from today. */
    private void shift(String userId, ZonedDateTime start, ZonedDateTime end) throws Exception {
        data(put("/projects/" + DEMO_PROJECT + "/workers/" + userId + "/shifts"), Map.of("shifts",
            List.of(Map.of("shiftStart", hour(start), "shiftEnd", hour(end), "workDays", EVERY_DAY))));
    }

    @SafeVarargs
    private void crew(String workOrderId, Map<String, Object>... members) throws Exception {
        data(put("/work-orders/" + workOrderId + "/crew"), Map.of("members", List.of(members)));
    }

    private String equipment() throws Exception {
        String code = "CREW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return data(post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentCode", code, "equipmentName", code.toLowerCase(),
            "equipmentType", "machine", "details", Map.of("capacityPerHour", 10))).path("equipmentId").asText();
    }

    private static String hour(ZonedDateTime at) {
        return String.format("%02d:00", at.getHour());
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
