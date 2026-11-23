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

/** Work instruction revisions and run checklists (docs/domain/work-instruction.md) against real Postgres. */
@AutoConfigureMockMvc
class WorkInstructionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void revisionsAreDraftedReleasedAndRetired() throws Exception {
        String bread = item("WI-BREAD-" + tag());
        String first = draft(bread, "Baking bread");
        create(bread, "Another draft").andExpect(status().isConflict());
        call(put("/work-instructions/" + first), json(text("Baking bread", "Mind the oven.", "ftp://files/wi.pdf")))
            .andExpect(status().isBadRequest());
        call(put("/work-instructions/" + first), json(text("Baking white bread", "Mind the oven.", "https://docs.example.com/wi-1.pdf")))
            .andExpect(jsonPath("$.data.title").value("Baking white bread"))
            .andExpect(jsonPath("$.data.documentUrl").value("https://docs.example.com/wi-1.pdf"));
        call(post("/work-instructions/" + first + "/release")).andExpect(status().isBadRequest());

        step(first, "Preheat the oven", true, true, "Oven °C").andExpect(status().isOk());
        step(first, "Knead the dough", true, false, null).andExpect(status().isOk());
        JsonNode draft = data(step(first, "Wipe the table", false, false, null)
            .andExpect(jsonPath("$.data.steps.length()").value(3))
            .andExpect(jsonPath("$.data.steps[0].recordsValue").value(true))
            .andExpect(jsonPath("$.data.steps[0].valueLabel").value("Oven °C"))
            .andExpect(jsonPath("$.data.steps[2].required").value(false)));
        step(first, " ", true, false, null).andExpect(status().isBadRequest());
        // Removing the second step numbers the rest from 1 again.
        call(delete("/work-instructions/" + first + "/steps/" + draft.path("steps").get(1).path("stepId").asText()))
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[1].stepNo").value(2))
            .andExpect(jsonPath("$.data.steps[1].text").value("Wipe the table"));
        step(first, "Knead the dough", true, false, null).andExpect(status().isOk());

        callAs("unrelated-user", post("/work-instructions/" + first + "/release")).andExpect(status().isForbidden());
        call(post("/work-instructions/" + first + "/release"))
            .andExpect(jsonPath("$.data.status").value("released"))
            .andExpect(jsonPath("$.data.releasedBy").value(DEMO_OWNER));
        call(put("/work-instructions/" + first), json(text("Changed", null, null)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("make a new revision")));

        // A new revision copies the text and steps; releasing it retires the first.
        String second = id(call(post("/work-instructions/" + first + "/revise"))
            .andExpect(jsonPath("$.data.revisionNo").value(2))
            .andExpect(jsonPath("$.data.status").value("draft"))
            .andExpect(jsonPath("$.data.steps.length()").value(3)), "instructionId");
        call(post("/work-instructions/" + first + "/revise")).andExpect(status().isConflict());
        call(post("/work-instructions/" + second + "/release")).andExpect(status().isOk());
        call(get("/work-instructions").param("projectId", DEMO_PROJECT).param("itemId", bread))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].revisionNo").value(2))
            .andExpect(jsonPath("$.data[0].status").value("released"))
            .andExpect(jsonPath("$.data[1].status").value("retired"));
        callAs("unrelated-user", get("/work-instructions").param("projectId", DEMO_PROJECT)).andExpect(status().isForbidden());

        // A draft can be deleted; its number is not used again.
        String third = id(call(post("/work-instructions/" + second + "/revise")), "instructionId");
        call(delete("/work-instructions/" + third)).andExpect(status().isOk());
        create(bread, "Fresh start").andExpect(jsonPath("$.data.revisionNo").value(4));
    }

    @Test
    void aRunWorksThroughTheReleasedStepsAndKeepsItsRevision() throws Exception {
        String roll = item("WI-ROLL-" + tag());
        String first = draft(roll, "Rolls");
        step(first, "Preheat the oven", true, true, "Oven °C").andExpect(status().isOk());
        step(first, "Shape the rolls", true, false, null).andExpect(status().isOk());
        JsonNode released = data(step(first, "Sweep up", false, false, null));
        call(post("/work-instructions/" + first + "/release")).andExpect(status().isOk());
        String preheat = released.path("steps").get(0).path("stepId").asText();
        String shape = released.path("steps").get(1).path("stepId").asText();

        String run = run(roll);
        instruction(run)
            .andExpect(jsonPath("$.data.open").value(true))
            .andExpect(jsonPath("$.data.instruction.revisionNo").value(1))
            .andExpect(jsonPath("$.data.requiredSteps").value(2))
            .andExpect(jsonPath("$.data.requiredDone").value(0))
            .andExpect(jsonPath("$.data.complete").value(false));

        check(run, preheat, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Oven °C")));
        check(run, preheat, "220").andExpect(jsonPath("$.data.checks[0].value").value("220"))
            .andExpect(jsonPath("$.data.checks[0].checkedBy").value(DEMO_OWNER));
        check(run, preheat, "220").andExpect(status().isConflict());
        check(run, "no-such-step", null).andExpect(status().isNotFound());
        check(run, shape, null).andExpect(jsonPath("$.data.requiredDone").value(2)).andExpect(jsonPath("$.data.complete").value(true));
        call(delete("/production-runs/" + run + "/instruction/steps/" + shape + "/check"))
            .andExpect(jsonPath("$.data.complete").value(false))
            .andExpect(jsonPath("$.data.undone.length()").value(1))
            .andExpect(jsonPath("$.data.undone[0].stepId").value(shape))
            .andExpect(jsonPath("$.data.undone[0].stepNo").value(2))
            .andExpect(jsonPath("$.data.undone[0].checkedBy").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.undone[0].undoneBy").value(DEMO_OWNER));
        // An undone confirmation stays as history (R6); the step can be confirmed and undone again.
        check(run, shape, null).andExpect(jsonPath("$.data.complete").value(true)).andExpect(jsonPath("$.data.undone.length()").value(1));
        call(delete("/production-runs/" + run + "/instruction/steps/" + shape + "/check"))
            .andExpect(jsonPath("$.data.undone.length()").value(2))
            .andExpect(jsonPath("$.data.checks.length()").value(1));
        call(delete("/production-runs/" + run + "/instruction/steps/" + shape + "/check")).andExpect(status().isNotFound());
        callAs("unrelated-user", get("/production-runs/" + run + "/instruction")).andExpect(status().isForbidden());

        // A new release does not change a checklist under way; a new run gets the new revision.
        String second = id(call(post("/work-instructions/" + first + "/revise")), "instructionId");
        call(post("/work-instructions/" + second + "/release")).andExpect(status().isOk());
        instruction(run).andExpect(jsonPath("$.data.instruction.revisionNo").value(1));
        instruction(run(roll)).andExpect(jsonPath("$.data.instruction.revisionNo").value(2));

        // A finished run's checklist is closed; a product without an instruction has an empty, complete one.
        call(post("/production-runs/" + run + "/finish"), "{}").andExpect(status().isOk());
        instruction(run).andExpect(jsonPath("$.data.open").value(false));
        check(run, shape, null).andExpect(status().isConflict());
        instruction(run(item("WI-NONE-" + tag())))
            .andExpect(jsonPath("$.data.instruction").isEmpty())
            .andExpect(jsonPath("$.data.complete").value(true));
    }

    @Test
    void anInstructionCanKeepARunFromFinishingUntilItsRequiredStepsAreConfirmed() throws Exception {
        String tart = item("WI-TART-" + tag());
        String first = draft(tart, "Tarts");
        Map<String, Object> strict = text("Tarts", null, null);
        strict.put("blocksFinish", true);
        call(put("/work-instructions/" + first), json(strict)).andExpect(jsonPath("$.data.blocksFinish").value(true));
        JsonNode released = data(step(first, "Check the oven", true, false, null));
        step(first, "Tidy up", false, false, null).andExpect(status().isOk());
        call(post("/work-instructions/" + first + "/release")).andExpect(status().isOk());
        // A new revision keeps the setting.
        String second = id(call(post("/work-instructions/" + first + "/revise")).andExpect(jsonPath("$.data.blocksFinish").value(true)),
            "instructionId");
        call(delete("/work-instructions/" + second)).andExpect(status().isOk());

        String run = run(tart);
        call(post("/production-runs/" + run + "/finish"), "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("0 of 1 done")));
        check(run, released.path("steps").get(0).path("stepId").asText(), null).andExpect(status().isOk());
        // The optional step does not hold it back.
        call(post("/production-runs/" + run + "/finish"), "{}").andExpect(status().isOk());
    }

    @Test
    void aValueOutsideItsStepsLimitsIsRecordedAndMarked() throws Exception {
        String bun = item("WI-BUN-" + tag());
        String draft = draft(bun, "Buns");
        stepWithLimits(draft, "Proof", false, 30, 40)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Limits need a step that records a value."));
        stepWithLimits(draft, "Bake", true, 230, 200)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("valueMin must not be above valueMax."));
        String bake = data(stepWithLimits(draft, "Bake", true, 200, 230)
                .andExpect(jsonPath("$.data.steps[0].valueMin").value(200))
                .andExpect(jsonPath("$.data.steps[0].valueMax").value(230)))
            .path("steps").get(0).path("stepId").asText();
        call(post("/work-instructions/" + draft + "/release")).andExpect(status().isOk());
        // A new revision keeps the limits.
        call(post("/work-instructions/" + draft + "/revise")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].valueMax").value(230));

        String run = run(bun);
        check(run, bake, "hot")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Step 1 records Oven °C as a number, such as 200."));
        check(run, bake, "250")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks[0].outOfLimits").value(true))
            .andExpect(jsonPath("$.data.complete").value(true));
        call(delete("/production-runs/" + run + "/instruction/steps/" + bake + "/check")).andExpect(status().isOk());
        check(run, bake, "215").andExpect(jsonPath("$.data.checks[0].outOfLimits").value(false));
        call(post("/production-runs/" + run + "/finish"), "{}").andExpect(status().isOk());
    }

    private ResultActions stepWithLimits(String instructionId, String text, boolean recordsValue, int min, int max) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("recordsValue", recordsValue);
        body.put("valueLabel", recordsValue ? "Oven °C" : null);
        body.put("valueMin", min);
        body.put("valueMax", max);
        return call(post("/work-instructions/" + instructionId + "/steps"), json(body));
    }

    private String draft(String itemId, String title) throws Exception {
        return id(create(itemId, title).andExpect(jsonPath("$.data.status").value("draft")), "instructionId");
    }

    private ResultActions create(String itemId, String title) throws Exception {
        Map<String, Object> body = text(title, null, null);
        body.put("projectId", DEMO_PROJECT);
        body.put("itemId", itemId);
        return call(post("/work-instructions"), json(body));
    }

    private static Map<String, Object> text(String title, String body, String url) {
        Map<String, Object> text = new HashMap<>();
        text.put("title", title);
        text.put("body", body);
        text.put("documentUrl", url);
        return text;
    }

    private ResultActions step(String instructionId, String text, boolean required, boolean recordsValue, String label) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("required", required);
        body.put("recordsValue", recordsValue);
        body.put("valueLabel", label);
        return call(post("/work-instructions/" + instructionId + "/steps"), json(body));
    }

    private ResultActions check(String runId, String stepId, String value) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("value", value);
        return call(post("/production-runs/" + runId + "/instruction/steps/" + stepId + "/check"), json(body));
    }

    private ResultActions instruction(String runId) throws Exception {
        return call(get("/production-runs/" + runId + "/instruction")).andExpect(status().isOk());
    }

    private String run(String itemId) throws Exception {
        return id(call(post("/production-runs/start"), json(Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", itemId, "plannedOutputQty", 10))), "productionRunId");
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
        return callAs(DEMO_OWNER, request);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
