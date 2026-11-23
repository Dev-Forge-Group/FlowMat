package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@AutoConfigureMockMvc
class RunInstructionRevisionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void undoingTheLastConfirmationKeepsTheFirstConfirmedRevision() throws Exception {
        Fixture fixture = fixture(false);
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isOk());
        JsonNode newer = releaseNext(fixture, false);

        uncheck(fixture).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instruction.instructionId").value(fixture.instructionId()))
            .andExpect(jsonPath("$.data.checks.length()").value(0));
        view(fixture).andExpect(jsonPath("$.data.instruction.revisionNo").value(1));
        check(fixture, newer.path("steps").get(0).path("stepId").asText(), Map.of()).andExpect(status().isNotFound());
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instruction.revisionNo").value(1));
    }

    @Test
    void undoingTheLastConfirmationCannotBypassThePinnedFinishRequirement() throws Exception {
        Fixture fixture = fixture(false);
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isOk());
        releaseNext(fixture, false);
        uncheck(fixture).andExpect(status().isOk());

        call(post("/production-runs/" + fixture.runId() + "/finish"), Map.of()).andExpect(status().isConflict());
        call(get("/production-runs/" + fixture.runId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runStatus").value("running"));
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isOk());
        call(post("/production-runs/" + fixture.runId() + "/finish"), Map.of()).andExpect(status().isOk());
    }

    @Test
    void viewingOrAFailedFirstConfirmationDoesNotPinTheRevision() throws Exception {
        Fixture fixture = fixture(true);
        view(fixture).andExpect(jsonPath("$.data.instruction.revisionNo").value(1));
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isBadRequest());
        JsonNode newer = releaseNext(fixture, false);

        view(fixture).andExpect(jsonPath("$.data.instruction.revisionNo").value(2));
        check(fixture, newer.path("steps").get(0).path("stepId").asText(), Map.of("value", "200"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.instruction.revisionNo").value(2));
    }

    @Test
    void undoingALegacyCheckPersistsItsRevisionBeforeDeletingTheLastCheck() throws Exception {
        Fixture fixture = fixture(false);
        check(fixture, fixture.stepId(), Map.of()).andExpect(status().isOk());
        // Represent a confirmation made by an older instance during a rolling deployment.
        jdbcTemplate.update("update production_run set work_instruction_id = null where production_run_id = ?", fixture.runId());
        releaseNext(fixture, false);

        uncheck(fixture).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instruction.instructionId").value(fixture.instructionId()));
        view(fixture).andExpect(jsonPath("$.data.instruction.revisionNo").value(1));
        call(post("/production-runs/" + fixture.runId() + "/finish"), Map.of()).andExpect(status().isConflict());
    }

    private Fixture fixture(boolean recordsValue) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String itemId = data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "PIN-" + tag,
            "itemName", "Revision pin " + tag, "unitId", "unit_ea"))).path("itemId").asText();
        String instructionId = data(call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT,
            "itemId", itemId, "title", "Pinned instruction " + tag, "blocksFinish", true))).path("instructionId").asText();
        JsonNode instruction = data(call(post("/work-instructions/" + instructionId + "/steps"), Map.of("text", "Required step",
            "required", true, "recordsValue", recordsValue, "valueLabel", "Temperature")));
        String stepId = instruction.path("steps").get(0).path("stepId").asText();
        data(call(post("/work-instructions/" + instructionId + "/release")));
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Revision pin " + tag))).path("workflowId").asText();
        String runId = data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT,
            "workflowId", workflowId, "targetItemId", itemId, "plannedOutputQty", 1))).path("productionRunId").asText();
        return new Fixture(runId, instructionId, stepId);
    }

    private JsonNode releaseNext(Fixture fixture, boolean blocksFinish) throws Exception {
        String id = data(call(post("/work-instructions/" + fixture.instructionId() + "/revise"))).path("instructionId").asText();
        data(call(put("/work-instructions/" + id), Map.of("title", "Next revision", "blocksFinish", blocksFinish)));
        return data(call(post("/work-instructions/" + id + "/release")));
    }

    private ResultActions view(Fixture fixture) throws Exception {
        return call(get("/production-runs/" + fixture.runId() + "/instruction")).andExpect(status().isOk());
    }

    private ResultActions check(Fixture fixture, String stepId, Map<String, ?> body) throws Exception {
        return call(post("/production-runs/" + fixture.runId() + "/instruction/steps/" + stepId + "/check"), body);
    }

    private ResultActions uncheck(Fixture fixture) throws Exception {
        return call(delete("/production-runs/" + fixture.runId() + "/instruction/steps/" + fixture.stepId() + "/check"));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private record Fixture(String runId, String instructionId, String stepId) {
    }
}
