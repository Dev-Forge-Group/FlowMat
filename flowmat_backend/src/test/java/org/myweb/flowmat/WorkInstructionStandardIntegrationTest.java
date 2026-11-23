package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A work instruction step that follows an inspection standard's limits (docs/domain/work-instruction.md W13-W15). */
@AutoConfigureMockMvc
class WorkInstructionStandardIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aStepFollowsItsStandardsLimitsUntilReleaseAndANewRevisionCatchesUp() throws Exception {
        String bread = item("WI-STD-" + tag());
        String other = item("WI-STD-" + tag());
        String moisture = id(standard(bread, "Moisture", "production", 10, 12), "standardId");
        String seal = id(standard(bread, "Seal", "receipt", null, null), "standardId");
        String elsewhere = id(standard(other, "Moisture", "production", 10, 12), "standardId");
        String draft = id(call(post("/work-instructions"), json(Map.of("projectId", DEMO_PROJECT, "itemId", bread, "title", "Bread"))),
            "instructionId");

        step(draft, false, moisture, null, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A standard needs a step that records a value."));
        step(draft, true, seal, null, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A receipt standard is for incoming LOTs; follow a production one."));
        step(draft, true, elsewhere, null, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The standard is not an active inspection standard of this product."));
        step(draft, true, moisture, 10, 13)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The standard sets the limits; leave them empty or send the same."));
        // The limits and the value's name come from the standard (W13).
        String stepId = data(step(draft, true, moisture, 10, null)
                .andExpect(jsonPath("$.data.steps[0].valueLabel").value("Moisture %"))
                .andExpect(jsonPath("$.data.steps[0].valueMin").value(10))
                .andExpect(jsonPath("$.data.steps[0].valueMax").value(12))
                .andExpect(jsonPath("$.data.steps[0].standardId").value(moisture))
                .andExpect(jsonPath("$.data.steps[0].standardName").value("Moisture"))
                .andExpect(jsonPath("$.data.steps[0].standardStatus").value("current")))
            .path("steps").get(0).path("stepId").asText();

        // A changed standard shows on the draft and is taken when it is released (W14).
        changeStandard(moisture, 10, 13, true);
        list(bread).andExpect(jsonPath("$.data[0].steps[0].valueMax").value(12))
            .andExpect(jsonPath("$.data[0].steps[0].standardStatus").value("changed"));
        call(post("/work-instructions/" + draft + "/release")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].valueMax").value(13))
            .andExpect(jsonPath("$.data.steps[0].standardStatus").value("current"));

        // A released revision keeps its limits; runs check against them.
        changeStandard(moisture, 9, 13, true);
        list(bread).andExpect(jsonPath("$.data[0].steps[0].valueMin").value(10))
            .andExpect(jsonPath("$.data[0].steps[0].standardStatus").value("changed"));
        String run = id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", bread, "plannedOutputQty", 10))), "productionRunId");
        call(post("/production-runs/" + run + "/instruction/steps/" + stepId + "/check"), json(Map.of("value", "9.5")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks[0].outOfLimits").value(true));

        // A new revision follows the standard as it is now; one no longer active stops its release.
        String revision = id(call(post("/work-instructions/" + draft + "/revise")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].valueMin").value(9))
            .andExpect(jsonPath("$.data.steps[0].standardId").value(moisture))
            .andExpect(jsonPath("$.data.steps[0].standardStatus").value("current")), "instructionId");
        changeStandard(moisture, 9, 13, false);
        list(bread).andExpect(jsonPath("$.data[0].steps[0].standardStatus").value("inactive"));
        call(post("/work-instructions/" + revision + "/release"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString(
                "Step 1 follows the Moisture standard, which is no longer an active production standard")));
    }

    @Test
    void aConfirmedStepThatFollowsAStandardIsTheRunsInspectionToo() throws Exception {
        String bread = item("WI-INS-" + tag());
        String moisture = id(standard(bread, "Moisture", "production", 10, 12), "standardId");
        String visual = id(standard(bread, "Visual", "production", null, null), "standardId");
        String draft = id(call(post("/work-instructions"), json(Map.of("projectId", DEMO_PROJECT, "itemId", bread, "title", "Bread"))),
            "instructionId");
        String measured = data(step(draft, true, moisture, null, null)).path("steps").get(0).path("stepId").asText();
        String looked = data(step(draft, true, visual, null, null)).path("steps").get(1).path("stepId").asText();
        call(post("/work-instructions/" + draft + "/release")).andExpect(status().isOk());
        String run = id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", bread, "plannedOutputQty", 10))), "productionRunId");

        // A step whose standard has limits takes no result: its value decides (R10).
        call(post("/production-runs/" + run + "/instruction/steps/" + measured + "/check"),
            json(Map.of("value", "11", "result", "pass")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Step 1 takes no result; its value decides the Moisture result."));
        // The confirmed number is the run's Moisture inspection, judged by the standard (R9).
        String inspection = data(confirm(run, measured, "11")
                .andExpect(jsonPath("$.data.checks[0].inspectionResult").value("pass")))
            .path("checks").get(0).path("inspectionId").asText();
        call(get("/production-runs/" + run + "/quality-checklist"))
            .andExpect(jsonPath("$.data.lines[?(@.standardId == '" + moisture + "')].status").value("pass"))
            .andExpect(jsonPath("$.data.lines[?(@.standardId == '" + moisture + "')].inspectionId").value(inspection));
        call(get("/quality-inspections").param("projectId", DEMO_PROJECT).param("productionRunId", run))
            .andExpect(jsonPath("$.data[0].standardId").value(moisture))
            .andExpect(jsonPath("$.data[0].measuredValue").value(11))
            .andExpect(jsonPath("$.data[0].note").value("Work instruction step 1: Check the dough"));

        // A standard without limits leaves pass or fail to the person confirming (R10); the person's result is required.
        call(post("/production-runs/" + run + "/instruction/steps/" + looked + "/check"), json(Map.of("value", "smooth")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Step 2 follows the Visual check; say whether it passed (pass or fail)."));
        call(post("/production-runs/" + run + "/instruction/steps/" + looked + "/check"), json(Map.of("value", "smooth", "result", "Pass")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks[1].inspectionResult").value("pass"));
        call(get("/production-runs/" + run + "/quality-checklist"))
            .andExpect(jsonPath("$.data.lines[?(@.standardId == '" + visual + "')].status").value("pass"));

        // Undoing keeps the inspection as a record; confirming again records the newer one, which the checklist follows.
        call(delete("/production-runs/" + run + "/instruction/steps/" + measured + "/check")).andExpect(status().isOk());
        confirm(run, measured, "13")
            .andExpect(jsonPath("$.data.checks[0].inspectionResult").value("fail"));
        call(get("/production-runs/" + run + "/quality-checklist"))
            .andExpect(jsonPath("$.data.lines[?(@.standardId == '" + moisture + "')].status").value("fail"))
            .andExpect(jsonPath("$.data.failed").value(1));
        call(get("/quality-inspections").param("projectId", DEMO_PROJECT).param("productionRunId", run))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.standardId == '" + visual + "')].note")
                .value("Work instruction step 2: Check the dough (smooth)"));
    }

    private ResultActions confirm(String runId, String stepId, String value) throws Exception {
        return call(post("/production-runs/" + runId + "/instruction/steps/" + stepId + "/check"), json(Map.of("value", value)))
            .andExpect(status().isOk());
    }

    private ResultActions standard(String item, String check, String stage, Integer min, Integer max) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", DEMO_PROJECT);
        body.put("itemId", item);
        body.put("inspectionType", check);
        body.put("stage", stage);
        body.put("standardMin", min);
        body.put("standardMax", max);
        body.put("unit", "%");
        body.put("required", false);
        return call(post("/inspection-standards"), json(body)).andExpect(status().isOk());
    }

    private void changeStandard(String standardId, int min, int max, boolean active) throws Exception {
        call(put("/inspection-standards/" + standardId), json(Map.of("inspectionType", "Moisture", "stage", "production",
            "standardMin", min, "standardMax", max, "unit", "%", "required", false, "active", active)))
            .andExpect(status().isOk());
    }

    private ResultActions step(String instructionId, boolean recordsValue, String standardId, Integer min, Integer max) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "Check the dough");
        body.put("recordsValue", recordsValue);
        body.put("standardId", standardId);
        body.put("valueMin", min);
        body.put("valueMax", max);
        return call(post("/work-instructions/" + instructionId + "/steps"), json(body));
    }

    private ResultActions list(String itemId) throws Exception {
        return call(get("/work-instructions").param("projectId", DEMO_PROJECT).param("itemId", itemId)).andExpect(status().isOk());
    }

    private String item(String code) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "product", "unitId", "unit_ea"))), "itemId");
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
