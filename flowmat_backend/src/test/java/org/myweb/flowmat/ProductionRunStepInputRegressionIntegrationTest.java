package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Failure reporting must reject text PostgreSQL cannot store before changing a running production step. */
@AutoConfigureMockMvc
class ProductionRunStepInputRegressionIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    static Stream<Arguments> invalidFailureReports() {
        return Stream.of("errorCode", "errorMessage").flatMap(field -> Stream.of(
            "bad\\u0000text", "\\u0000BLOCKED", "bad\\ud800text", "bad\\udc00text")
            .map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidFailureReports")
    void invalidCharactersAreAClientErrorAndDoNotEndTheAttempt(String field, String escapedValue) throws Exception {
        Run run = runningStep();
        JsonNode before = state(run);
        String code = "errorCode".equals(field) ? escapedValue : "BLOCKED";
        String message = "errorMessage".equals(field) ? escapedValue : "Fixture failure";
        String body = "{\"errorCode\":\"" + code + "\",\"errorMessage\":\"" + message + "\"}";

        mvc.perform(post(stepPath(run) + "/fail").header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", Matchers.containsString(field)));

        assertEquals(before, state(run), "Invalid failure text must leave the running attempt and events untouched");
    }

    @Test
    void validUnicodeAndErrorCodeTrimmingStillWork() throws Exception {
        Run run = runningStep();
        String message = "  Oven stopped \ud83d\udd25 \ud55c\uae00  ";
        JsonNode result = data(post(stepPath(run) + "/fail"), Map.of("errorCode", "  OVEN  ", "errorMessage", message));
        assertEquals("failed", result.path("status").asText());
        assertEquals("OVEN", result.path("errorCode").asText());
        assertEquals(message, result.path("errorMessage").asText());
        JsonNode attempts = data(get("/flow-runs/" + run.flowRunId() + "/steps/" + run.stepId() + "/attempts"), null);
        assertEquals(1, attempts.size());
        assertEquals("failed", attempts.get(0).path("status").asText());
        assertEquals(message, attempts.get(0).path("errorMessage").asText());
    }

    private record Run(String productionRunId, String stepId, String flowRunId) {}

    private Run runningStep() throws Exception {
        String workflow = data(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Failure text " + UUID.randomUUID())).path("workflowId").asText();
        data(post("/processes"), Map.of("workflowId", workflow, "processName", "Mix", "nodeType", "process"));
        String revision = data(post("/workflows/" + workflow + "/revisions"), null).path("workflowRevisionId").asText();
        JsonNode production = data(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflow,
            "workflowRevisionId", revision, "plannedOutputQty", 10, "runType", "simulation"));
        assertEquals(true, production.path("bomId").isNull());
        String runId = production.path("productionRunId").asText();
        JsonNode steps = data(get("/production-runs/" + runId + "/steps"), null);
        assertEquals(1, steps.size());
        String stepId = steps.get(0).path("stepId").asText();
        JsonNode flows = data(get("/flow-runs").param("workflowId", workflow), null);
        assertEquals(1, flows.size());
        Run run = new Run(runId, stepId, flows.get(0).path("flowRunId").asText());
        data(post(stepPath(run) + "/start"), null);
        return run;
    }

    private JsonNode state(Run run) throws Exception {
        ObjectNode state = mapper.createObjectNode();
        state.set("steps", data(get("/production-runs/" + run.productionRunId() + "/steps"), null));
        state.set("attempts", data(get("/flow-runs/" + run.flowRunId() + "/steps/" + run.stepId() + "/attempts"), null));
        state.set("events", data(get("/flow-runs/" + run.flowRunId() + "/events"), null));
        return state;
    }

    private static String stepPath(Run run) {
        return "/production-runs/" + run.productionRunId() + "/steps/" + run.stepId();
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        var result = mvc.perform(request.header("Authorization", bearer())).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getRequest().getRequestURI());
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String bearer() {
        return "Bearer " + jwt.generateAccessToken(DEMO_OWNER);
    }
}
