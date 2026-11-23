package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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

/** Equipment running time with the rate snapshot (docs/domain/equipment-setup-cost.md ER1-ER7) against real Postgres. */
@AutoConfigureMockMvc
class RunEquipmentTimeIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void runningTimeKeepsItsRateIsSuggestedFromTheRunAndStaysApartFromMaterialCost() throws Exception {
        String oven = equipment("Running oven");
        rate(oven, 30, 0);
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Running order", "targetQuantity", 1)))).path("workOrderId").asText();
        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", oven))).andExpect(status().isOk());
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", order, "plannedOutputQty", 1)))).path("productionRunId").asText();
        String path = "/production-runs/" + run + "/equipment-time";

        // A run started two hours ago with a 20-minute setup suggests 100 minutes of running (ER4).
        jdbc.update("update production_run set created_at = created_at - interval '2 hours' where production_run_id = ?", run);
        call(post("/production-runs/" + run + "/setups"), json(Map.of("requestId", UUID.randomUUID().toString(), "setupMinutes", 20)))
            .andExpect(status().isOk());
        call(get(path))
            .andExpect(jsonPath("$.data.defaultEquipmentId").value(oven))
            .andExpect(jsonPath("$.data.suggestedMinutes").value(100))
            .andExpect(jsonPath("$.data.runningMinutes").value(0))
            .andExpect(jsonPath("$.data.costComplete").value(true));

        // 90 minutes at 30 an hour on the work order's oven (ER2); the suggestion shrinks by what is recorded.
        Map<String, Object> first = time(null, 90, "Bake");
        call(post(path), json(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[0].equipmentId").value(oven))
            .andExpect(jsonPath("$.data.lines[0].hourlyCost").value(30))
            .andExpect(jsonPath("$.data.lines[0].runningCost").value(45))
            .andExpect(jsonPath("$.data.runningCost").value(45))
            .andExpect(jsonPath("$.data.suggestedMinutes").value(10));

        // The same record again is answered with the run as it is; different content under its key is refused.
        call(post(path), json(first)).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines.length()").value(1));
        Map<String, Object> changed = new HashMap<>(first);
        changed.put("runningMinutes", 80);
        call(post(path), json(changed)).andExpect(status().isConflict());

        // A later rate counts for later records only.
        rate(oven, 60, 1);
        String second = data(call(post(path), json(time(oven, 10, null)))).path("lines").get(1).path("runEquipmentTimeId").asText();
        call(get(path))
            .andExpect(jsonPath("$.data.lines[0].runningCost").value(45))
            .andExpect(jsonPath("$.data.lines[1].runningCost").value(10))
            .andExpect(jsonPath("$.data.runningMinutes").value(100))
            .andExpect(jsonPath("$.data.suggestedMinutes").doesNotExist());

        // Input is checked; a cancel needs a reason and leaves the row for the record (ER3).
        call(post(path), json(time(oven, 0, null))).andExpect(status().isBadRequest());
        call(post(path), json(time(oven, 10_081, null))).andExpect(status().isBadRequest());
        call(post(path), "{\"requestId\":\"not-a-uuid\",\"runningMinutes\":5}").andExpect(status().isBadRequest());
        call(post(path + "/" + second + "/cancel"), "{}").andExpect(status().isBadRequest());
        call(post(path + "/" + second + "/cancel"), json(Map.of("reason", "Typed twice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[1].cancelled").value(true))
            .andExpect(jsonPath("$.data.runningMinutes").value(90))
            .andExpect(jsonPath("$.data.runningCost").value(45));

        // Material cost does not include running time (ER5); outsiders cannot read it; a finished run takes no more.
        call(get("/production-runs/" + run + "/cost")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.materialCost").value(0));
        callAs("unrelated-user", get(path)).andExpect(status().isForbidden());
        call(post("/production-runs/" + run + "/finish"), json(Map.of("actualOutputQty", 1))).andExpect(status().isOk());
        call(post(path), json(time(oven, 5, null))).andExpect(status().isBadRequest());
    }

    @Test
    void anOrdersPlannedRunningTimeStandsBesideItsRunsRecordedTime() throws Exception {
        // Capacity 10 an hour (the helper's) and 30 an hour: 5 units plan 30 minutes, 15 at today's rate (ER8).
        String oven = equipment("Comparison oven");
        rate(oven, 30, 0);
        String order = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "Compared order", "targetQuantity", 5)))).path("workOrderId").asText();
        call(put("/work-orders/" + order + "/equipment"), json(Map.of("equipmentId", oven))).andExpect(status().isOk());
        call(post("/work-orders/" + order + "/approve")).andExpect(status().isOk());
        String compare = "/work-orders/" + order + "/running-comparison";
        call(get(compare))
            .andExpect(jsonPath("$.data.capacityPerHour").value(10))
            .andExpect(jsonPath("$.data.plannedMinutes").value(30))
            .andExpect(jsonPath("$.data.plannedCost").value(15))
            .andExpect(jsonPath("$.data.runs").value(0))
            .andExpect(jsonPath("$.data.actualMinutes").value(0));

        String run = data(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderId", order, "plannedOutputQty", 5)))).path("productionRunId").asText();
        call(post("/production-runs/" + run + "/equipment-time"), json(time(null, 40, null))).andExpect(status().isOk());
        call(get(compare))
            .andExpect(jsonPath("$.data.runs").value(1))
            .andExpect(jsonPath("$.data.actualMinutes").value(40))
            .andExpect(jsonPath("$.data.actualCost").value(20))
            .andExpect(jsonPath("$.data.actualCostComplete").value(true));

        // Without equipment there is no plan to compare with.
        String bare = data(call(post("/work-orders"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "workOrderTitle", "No equipment", "targetQuantity", 5)))).path("workOrderId").asText();
        call(get("/work-orders/" + bare + "/running-comparison"))
            .andExpect(jsonPath("$.data.plannedMinutes").doesNotExist())
            .andExpect(jsonPath("$.data.plannedCost").doesNotExist());
        callAs("unrelated-user", get(compare)).andExpect(status().isForbidden());
    }

    private static Map<String, Object> time(String equipmentId, int minutes, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("runningMinutes", minutes);
        if (equipmentId != null) {
            body.put("equipmentId", equipmentId);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private String equipment(String name) throws Exception {
        return data(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentName", name, "equipmentType", "machine",
            "details", Map.of("capacityPerHour", 10))))).path("equipmentId").asText();
    }

    private void rate(String equipmentId, int hourlyCost, int expectedVersion) throws Exception {
        call(put("/equipments/" + equipmentId + "/hourly-cost"), json(Map.of("hourlyCost", hourlyCost, "expectedVersion", expectedVersion)))
            .andExpect(status().isOk());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions callAs(String user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)));
    }
}
