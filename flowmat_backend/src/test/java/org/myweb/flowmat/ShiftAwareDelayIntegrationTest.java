package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A connection delay in a production run follows the work order equipment's working time
 * (docs/architecture/adr/ADR-015-shift-aware-delay.md). The delay is a hundredth of a second (the smallest delayTimeSec), so
 * it is over by the next request.
 */
@AutoConfigureMockMvc
class ShiftAwareDelayIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void aDelayEndingOffShiftWaitsForTheEquipmentsNextShiftUnlessItWorksNow() throws Exception {
        Line line = new Line();
        String press = equipment();
        ZoneId zone = ZoneId.of(data(get("/equipments/" + press + "/schedule"), null).path("timeZone").asText());
        // One hour every day, two to three hours from now: the delay always ends before it.
        ZonedDateTime opens = ZonedDateTime.now(zone).plusHours(3).truncatedTo(ChronoUnit.HOURS);
        data(put("/equipments/" + press + "/calendar"), Map.of("shiftStart", hour(opens), "shiftEnd", hour(opens.plusHours(1)),
            "workDays", EVERY_DAY));
        String run = line.start(line.order(press));

        OffsetDateTime ready = completeMix(run, line);
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(opens.toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        JsonNode scheduled = scheduledEvent(line.flowRunOf(run), bake.path("stepId").asText());
        assertEquals(ready.toInstant(), OffsetDateTime.parse(scheduled.path("readyAt").asText()).toInstant());
        assertEquals(press, scheduled.path("equipmentId").asText());

        // Off shift it waits; overtime given as today's own shifts lets it start now (decision 6).
        String baking = bake.path("stepId").asText();
        call(post(step(run, baking, "start")), null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Step cannot start before its scheduledAt."));
        data(put("/equipments/" + press + "/days/" + ZonedDateTime.now(zone).toLocalDate()),
            Map.of("shifts", List.of(Map.of("shiftStart", "00:00", "shiftEnd", "00:00")), "reason", "Overtime"));
        assertEquals("running", data(post(step(run, baking, "start")), null).path("status").asText());
    }

    @Test
    void aDelayEndingInAShiftOrWithoutEquipmentKeepsItsTime() throws Exception {
        Line line = new Line();
        String press = equipment();
        data(put("/equipments/" + press + "/calendar"), Map.of("shiftStart", "00:00", "shiftEnd", "00:00", "workDays", EVERY_DAY));
        String inShift = line.start(line.order(press));
        OffsetDateTime ready = completeMix(inShift, line);
        JsonNode bake = forNode(steps(inShift), line.bake);
        assertEquals(ready.toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        assertFalse(scheduledEvent(line.flowRunOf(inShift), bake.path("stepId").asText()).has("readyAt"));

        String noEquipment = line.start(line.order(null));
        OffsetDateTime alsoReady = completeMix(noEquipment, line);
        JsonNode other = forNode(steps(noEquipment), line.bake);
        assertEquals(alsoReady.toInstant(), OffsetDateTime.parse(other.path("scheduledAt").asText()).toInstant());
        assertFalse(scheduledEvent(line.flowRunOf(noEquipment), other.path("stepId").asText()).has("readyAt"));
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
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Shift " + UUID.randomUUID()))
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
                "workOrderTitle", "Shift " + UUID.randomUUID(), "targetQuantity", 10,
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

    private String equipment() throws Exception {
        String code = "SHIFT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
