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

/** Setup corrections of finished runs (docs/domain/equipment-setup-cost.md AS7-AS10) against real Postgres. */
@AutoConfigureMockMvc
class RunSetupCorrectionIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;

    @Test
    void aCorrectionCancelsAndAddsSetupsAtTheRateOfTheOriginalFinish() throws Exception {
        String press = equipment("Correction press");
        rate(press, 30, 0);
        String run = run();
        String first = data(call(post(setups(run)), setup(press, 45))).path("lines").get(0).path("runSetupId").asText();
        call(get(setups(run)), null).andExpect(jsonPath("$.data.lines[0].rateBasis").value("recorded"));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        // The rate goes up after the run finished; the corrected setup still takes the rate of the finish.
        rate(press, 90, 1);
        // A rate first set after the finish is not known for it: estimated and unknown.
        String later = equipment("Later bench");
        rate(later, 60, 0);
        call(get("/equipments/" + press + "/hourly-cost/history"), null)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].previousHourlyCost").value(30))
            .andExpect(jsonPath("$.data[0].hourlyCost").value(90))
            .andExpect(jsonPath("$.data[1].previousHourlyCost").isEmpty());

        Map<String, Object> request = Map.of("reason", "Setup was recorded on the wrong die", "lines", List.of(
            Map.of("kind", "cancel_setup", "targetRunSetupId", first),
            Map.of("kind", "add_setup", "equipmentId", press, "setupMinutes", 20),
            Map.of("kind", "add_setup", "equipmentId", later, "setupMinutes", 10)));
        String correction = data(call(post(corrections(run)), request)).path("productionRunCorrectionId").asText();
        call(post(corrections(run) + "/" + correction + "/approve"), Map.of())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("applied"))
            .andExpect(jsonPath("$.data.lines[1].createdRunSetupId").isNotEmpty());

        JsonNode after = data(call(get(setups(run)), null));
        JsonNode cancelled = null;
        JsonNode added = null;
        JsonNode estimated = null;
        for (JsonNode line : after.path("lines")) {
            if (first.equals(line.path("runSetupId").asText())) cancelled = line;
            else if (press.equals(line.path("equipmentId").asText())) added = line;
            else if (later.equals(line.path("equipmentId").asText())) estimated = line;
        }
        assertNotNull(cancelled);
        assertNotNull(added);
        assertNotNull(estimated);
        assertEquals(true, cancelled.path("cancelled").asBoolean());
        assertEquals("Correction #1: Setup was recorded on the wrong die", cancelled.path("cancelReason").asText());
        assertEquals(30.0, added.path("hourlyCost").asDouble());
        assertEquals(10.0, added.path("setupCost").asDouble());
        assertEquals("historical", added.path("rateBasis").asText());
        assertEquals("estimated", estimated.path("rateBasis").asText());
        assertEquals(true, estimated.path("setupCost").isNull());
        assertEquals(30, after.path("setupMinutes").asInt());
        assertEquals(false, after.path("costComplete").asBoolean());
    }

    @Test
    void setupCorrectionLinesAreChecked() throws Exception {
        String press = equipment("Checked press");
        String run = run();
        String setupId = data(call(post(setups(run)), setup(press, 5))).path("lines").get(0).path("runSetupId").asText();
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1)).andExpect(status().isOk());
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(Map.of("kind", "cancel_setup", "targetRunSetupId", "missing"))))
            .andExpect(status().isNotFound());
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(Map.of("kind", "add_setup", "equipmentId", press, "setupMinutes", 0))))
            .andExpect(status().isBadRequest());
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(
            Map.of("kind", "add_setup", "equipmentId", "no-such-equipment", "setupMinutes", 5))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Equipment does not exist")));
        call(post(corrections(run)), Map.of("reason", "x", "lines", List.of(
            Map.of("kind", "cancel_setup", "targetRunSetupId", setupId), Map.of("kind", "cancel_setup", "targetRunSetupId", setupId))))
            .andExpect(status().isBadRequest());
    }

    private String run() throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private static String setups(String run) {
        return "/production-runs/" + run + "/setups";
    }

    private static String corrections(String run) {
        return "/production-runs/" + run + "/corrections";
    }

    private static Map<String, Object> setup(String equipmentId, int minutes) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("equipmentId", equipmentId);
        body.put("setupMinutes", minutes);
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
