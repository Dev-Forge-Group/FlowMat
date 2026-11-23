package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Running-time corrections of finished runs (docs/domain/equipment-setup-cost.md ER10-ER12) against real Postgres. */
@AutoConfigureMockMvc
class RunEquipmentTimeCorrectionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;

    @Test
    void aCorrectionCancelsAndAddsRunningTimeAtTheRateOfTheOriginalFinish() throws Exception {
        String oven = equipment("Correction oven");
        rate(oven, 30, 0);
        String run = run();
        String first = data(call(post(times(run)), time(oven, 45))).path("lines").get(0).path("runEquipmentTimeId").asText();
        call(get(times(run)), null).andExpect(jsonPath("$.data.lines[0].rateBasis").value("recorded"));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        // Recording on the finished run is refused; a correction is the way to change its time.
        call(post(times(run)), time(oven, 5)).andExpect(status().isBadRequest());
        // The rate goes up after the run finished; the corrected running time still takes the rate of the finish.
        rate(oven, 90, 1);
        // A rate first set after the finish is not known for it: estimated and unknown.
        String later = equipment("Later kiln");
        rate(later, 60, 0);

        Map<String, Object> request = Map.of("reason", "Oven time was counted twice", "lines", List.of(
            Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", first),
            Map.of("kind", "add_running_time", "equipmentId", oven, "runningMinutes", 20),
            Map.of("kind", "add_running_time", "equipmentId", later, "runningMinutes", 10)));
        String correction = data(call(post(corrections(run)), request)).path("productionRunCorrectionId").asText();
        // Nothing changes until the project owner approves.
        call(get(times(run)), null).andExpect(jsonPath("$.data.runningMinutes").value(45));
        call(post(corrections(run) + "/" + correction + "/approve"), Map.of())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("applied"))
            .andExpect(jsonPath("$.data.lines[0].kind").value("cancel_running_time"))
            .andExpect(jsonPath("$.data.lines[0].targetRunEquipmentTimeId").value(first))
            .andExpect(jsonPath("$.data.lines[1].runningMinutes").value(20))
            .andExpect(jsonPath("$.data.lines[1].equipmentId").value(oven))
            .andExpect(jsonPath("$.data.lines[1].createdRunEquipmentTimeId").isNotEmpty());

        JsonNode after = data(call(get(times(run)), null));
        JsonNode cancelled = null;
        JsonNode added = null;
        JsonNode estimated = null;
        for (JsonNode line : after.path("lines")) {
            if (first.equals(line.path("runEquipmentTimeId").asText())) cancelled = line;
            else if (oven.equals(line.path("equipmentId").asText())) added = line;
            else if (later.equals(line.path("equipmentId").asText())) estimated = line;
        }
        assertNotNull(cancelled);
        assertNotNull(added);
        assertNotNull(estimated);
        assertEquals(true, cancelled.path("cancelled").asBoolean());
        assertEquals("Correction #1: Oven time was counted twice", cancelled.path("cancelReason").asText());
        assertEquals(30.0, added.path("hourlyCost").asDouble());
        assertEquals(10.0, added.path("runningCost").asDouble());
        assertEquals("historical", added.path("rateBasis").asText());
        assertEquals("Correction #1: Oven time was counted twice", added.path("note").asText());
        assertEquals("estimated", estimated.path("rateBasis").asText());
        assertEquals(true, estimated.path("runningCost").isNull());
        assertEquals(30, after.path("runningMinutes").asInt());
        assertEquals(false, after.path("costComplete").asBoolean());
        // Still apart from material cost (ER5).
        call(get("/production-runs/" + run + "/cost"), null).andExpect(jsonPath("$.data.materialCost").value(0));
    }

    @Test
    void runningTimeCorrectionLinesAreChecked() throws Exception {
        String oven = equipment("Checked oven");
        String run = run();
        String timeId = data(call(post(times(run)), time(oven, 5))).path("lines").get(0).path("runEquipmentTimeId").asText();
        String gone = data(call(post(times(run)), time(oven, 7))).path("lines").get(1).path("runEquipmentTimeId").asText();
        call(post(times(run) + "/" + gone + "/cancel"), Map.of("reason", "typed twice")).andExpect(status().isOk());
        // An open run is not corrected: its time is recorded and cancelled on the run.
        call(post(corrections(run)), correction(Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", timeId)))
            .andExpect(status().isBadRequest());
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());

        call(post(corrections(run)), correction(Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", "missing")))
            .andExpect(status().isNotFound());
        call(post(corrections(run)), correction(Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", gone)))
            .andExpect(status().isConflict());
        call(post(corrections(run)), correction(Map.of("kind", "add_running_time", "equipmentId", oven, "runningMinutes", 0)))
            .andExpect(status().isBadRequest());
        call(post(corrections(run)), correction(Map.of("kind", "add_running_time", "equipmentId", oven, "runningMinutes", 10_081)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("runningMinutes must be whole minutes")));
        call(post(corrections(run)), correction(Map.of("kind", "add_running_time", "equipmentId", "no-such-equipment",
            "runningMinutes", 5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Equipment does not exist")));
        // This run has no work order, so there is no equipment to fall back on.
        call(post(corrections(run)), correction(Map.of("kind", "add_running_time", "runningMinutes", 5)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("this run's work order has none")));
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(
            Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", timeId),
            Map.of("kind", "cancel_running_time", "targetRunEquipmentTimeId", timeId))))
            .andExpect(status().isBadRequest());
        // The refused requests left nothing behind.
        call(get(corrections(run)), null).andExpect(jsonPath("$.data.length()").value(0));
    }

    private String run() throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private static String times(String run) {
        return "/production-runs/" + run + "/equipment-time";
    }

    private static String corrections(String run) {
        return "/production-runs/" + run + "/corrections";
    }

    private static Map<String, Object> correction(Map<String, Object> line) {
        return Map.of("reason", "x", "lines", List.of(line));
    }

    private static Map<String, Object> time(String equipmentId, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("equipmentId", equipmentId);
        body.put("runningMinutes", minutes);
        return body;
    }

    private String equipment(String name) throws Exception {
        return data(call(post("/equipments"), Map.of("projectId", DEMO_PROJECT, "equipmentName", name, "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10)))).path("equipmentId").asText();
    }

    private void rate(String equipmentId, int hourlyCost, int expectedVersion) throws Exception {
        call(put("/equipments/" + equipmentId + "/hourly-cost"), Map.of("hourlyCost", hourlyCost, "expectedVersion", expectedVersion))
            .andExpect(status().isOk());
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        return mvc.perform(request);
    }
}
