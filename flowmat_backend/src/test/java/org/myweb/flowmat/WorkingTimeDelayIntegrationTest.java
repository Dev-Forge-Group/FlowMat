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
 * A connection delay that counts only the work order's working time (docs/architecture/adr/ADR-022-working-time-delay.md):
 * in a production run it ends once the equipment has worked that long; without a work order it counts all time.
 */
@AutoConfigureMockMvc
class WorkingTimeDelayIntegrationTest extends IntegrationTestSupport {
    private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void aWorkingTimeDelayCountsOnlyTheEquipmentsShifts() throws Exception {
        // Two presses on one hour every day, two to three hours from now.
        String press = equipment();
        ZoneId zone = ZoneId.of(data(get("/equipments/" + press + "/schedule"), null).path("timeZone").asText());
        ZonedDateTime opens = ZonedDateTime.now(zone).plusHours(3).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> shift = Map.of("shiftStart", hour(opens), "shiftEnd", hour(opens.plusHours(1)), "workDays", EVERY_DAY);
        data(put("/equipments/" + press + "/calendar"), shift);
        String other = equipment();
        data(put("/equipments/" + other + "/calendar"), shift);

        // 30 working minutes end half an hour into the shift (decision 2); the step can start then, not before.
        Line half = new Line(1800);
        String run = half.start(half.order(press));
        completeMix(run, half);
        JsonNode bake = forNode(steps(run), half.bake);
        assertEquals(opens.plusMinutes(30).toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
        JsonNode scheduled = scheduledEvent(half.flowRunOf(run), bake.path("stepId").asText());
        assertEquals("working", scheduled.path("delayBasis").asText());
        assertFalse(scheduled.has("readyAt"));
        call(post(step(run, bake.path("stepId").asText(), "start")), null).andExpect(status().isConflict());

        // 90 working minutes take this shift's hour and half an hour of the next day's.
        Line longer = new Line(5400);
        String later = longer.start(longer.order(other));
        completeMix(later, longer);
        JsonNode next = forNode(steps(later), longer.bake);
        assertEquals(opens.plusDays(1).plusMinutes(30).toInstant(), OffsetDateTime.parse(next.path("scheduledAt").asText()).toInstant());
    }

    @Test
    void withoutAWorkOrderAWorkingTimeDelayCountsAllTime() throws Exception {
        Line line = new Line(1800);
        String run = data(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", line.workflow,
            "workflowRevisionId", line.revision, "plannedOutputQty", 10, "runType", "simulation")).path("productionRunId").asText();
        OffsetDateTime ended = completeMix(run, line);
        JsonNode bake = forNode(steps(run), line.bake);
        assertEquals(ended.plusMinutes(30).toInstant(), OffsetDateTime.parse(bake.path("scheduledAt").asText()).toInstant());
    }

    @Test
    void aConnectionKeepsWhatItsDelayCountsAndPublishesIt() throws Exception {
        Line line = new Line(60);
        JsonNode connection = data(get("/process-connections/" + line.connection), null);
        assertEquals("working", connection.path("delayBasis").asText());
        JsonNode revision = data(get("/workflows/" + line.workflow + "/revisions/" + line.revision), null);
        assertEquals("working", revision.path("snapshot").path("connections").get(0).path("delayBasis").asText());

        // Left out on create it counts all time; it changes only when sent, and only to the two bases.
        String pack = line.node("Pack");
        String plain = data(post("/process-connections"), Map.of("workflowId", line.workflow, "fromProcessId", line.bake,
            "toProcessId", pack)).path("connectionId").asText();
        assertEquals("elapsed", data(get("/process-connections/" + plain), null).path("delayBasis").asText());
        call(put("/process-connections/" + plain), Map.of("delayBasis", "calendar")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("delayBasis is elapsed or working."));
        String fill = line.node("Fill");
        call(post("/process-connections"), Map.of("workflowId", line.workflow, "fromProcessId", pack, "toProcessId", fill,
            "delayBasis", "always")).andExpect(status().isBadRequest());
        assertEquals("working", data(put("/process-connections/" + plain), Map.of("delayBasis", "Working")).path("delayBasis").asText());
        assertEquals("working", data(put("/process-connections/" + plain), Map.of("delayTimeSec", 5)).path("delayBasis").asText());
    }

    /** Starts and completes Mix and returns when it ended. */
    private OffsetDateTime completeMix(String run, Line line) throws Exception {
        String mix = forNode(steps(run), line.mix).path("stepId").asText();
        data(post(step(run, mix, "start")), null);
        JsonNode mixed = data(post(step(run, mix, "complete")), Map.of());
        return OffsetDateTime.parse(mixed.path("endedAt").asText()).truncatedTo(ChronoUnit.MICROS);
    }

    /** Mix → Bake with a delay of working time, published. */
    private final class Line {
        final String workflow;
        final String mix;
        final String bake;
        final String connection;
        final String revision;

        Line(int delaySeconds) throws Exception {
            workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Working " + UUID.randomUUID()))
                .path("workflowId").asText();
            mix = node("Mix");
            bake = node("Bake");
            connection = data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", mix, "toProcessId", bake,
                "delayTimeSec", delaySeconds, "delayBasis", "working")).path("connectionId").asText();
            revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        }

        String node(String name) throws Exception {
            return data(post("/processes"), Map.of("workflowId", workflow, "processName", name, "nodeType", "process"))
                .path("processId").asText();
        }

        /** An approved work order on this workflow and the equipment. */
        String order(String equipmentId) throws Exception {
            String order = data(post("/work-orders"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
                "workOrderTitle", "Working " + UUID.randomUUID(), "targetQuantity", 10,
                "plannedStartAt", "2030-01-07T09:00:00+09:00", "plannedEndAt", "2030-01-07T17:00:00+09:00")).path("workOrderId").asText();
            data(put("/work-orders/" + order + "/equipment"), Map.of("equipmentId", equipmentId));
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
        String code = "WORK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
