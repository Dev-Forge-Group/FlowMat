package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class FlowRunExactJsonIntegrationTest extends IntegrationTestSupport {
    private static final String PAYLOAD = "{\"integer\":9007199254740993,"
        + "\"decimal\":9007199254740993.123456789,\"nested\":[null,{\"text\":\"quoted \\\"value\\\"\\nnext\"}]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void graphCreateCompleteListAndLineageCarryExactJsonStringsThroughDownstreamSteps() throws Exception {
        String workflow = workflow();
        String source = process(workflow, "Source");
        String target = process(workflow, "Target");
        data(post("/process-connections"), mapper.writeValueAsString(Map.of("workflowId", workflow,
            "fromProcessId", source, "toProcessId", target)));
        String revision = data(post("/workflows/" + workflow + "/revisions"), null)
            .path("workflowRevisionId").asText();
        JsonNode run = data(post("/flow-runs/graph"), "{\"workflowId\":\"" + workflow
            + "\",\"workflowRevisionId\":\"" + revision + "\",\"runType\":\"test\",\"inputPayload\":" + PAYLOAD + "}");
        String runId = run.path("flowRunId").asText();
        assertExactPayload(run, "inputPayload");
        assertTrue(run.path("outputPayloadJson").isNull());

        JsonNode roots = data(get("/flow-runs/" + runId + "/steps"), null);
        assertEquals(1, roots.size());
        String sourceStep = roots.get(0).path("stepId").asText();
        assertExactPayload(roots.get(0), "inputSnapshot");
        data(post("/flow-runs/" + runId + "/steps/" + sourceStep + "/start"), null);
        JsonNode completed = data(post("/flow-runs/" + runId + "/steps/" + sourceStep + "/complete"),
            "{\"outputSnapshot\":" + PAYLOAD + "}");
        assertExactPayload(completed, "outputSnapshot");

        JsonNode steps = data(get("/flow-runs/" + runId + "/steps"), null);
        JsonNode downstream = findStep(steps, target);
        assertExactPayload(downstream, "inputSnapshot");
        String targetStep = downstream.path("stepId").asText();
        JsonNode lineage = data(get("/flow-runs/" + runId + "/steps/" + targetStep + "/lineage"), null);
        assertExactPayload(lineage.path("step"), "inputSnapshot");
        assertEquals(1, lineage.path("ancestors").size());
        assertExactPayload(lineage.path("ancestors").get(0), "outputSnapshot");

        data(post("/flow-runs/" + runId + "/steps/" + targetStep + "/start"), null);
        data(post("/flow-runs/" + runId + "/steps/" + targetStep + "/complete"), "{\"outputSnapshot\":{\"ok\":true}}");
        JsonNode finished = data(post("/flow-runs/" + runId + "/finish"), "{\"outputPayload\":" + PAYLOAD + "}");
        assertEquals("finished", finished.path("status").asText());
        assertExactPayload(finished, "outputPayload");
        assertExactPayload(data(get("/flow-runs/" + runId), null), "outputPayload");
        JsonNode listed = data(get("/flow-runs").param("workflowId", workflow), null);
        assertEquals(1, listed.size());
        assertExactPayload(listed.get(0), "inputPayload");
        assertExactPayload(listed.get(0), "outputPayload");
    }

    @Test
    void finishingFromRawJsonPreservesDecimalPrecisionBeforeResponseSerialization() throws Exception {
        boolean sharedMapperUsesBigDecimals = mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode ordinaryJsonBefore = mapper.readTree(PAYLOAD);
        String workflow = workflow();
        String revision = data(post("/workflows/" + workflow + "/revisions"), null)
            .path("workflowRevisionId").asText();
        String runId = data(post("/flow-runs"), mapper.writeValueAsString(Map.of("workflowId", workflow,
            "workflowRevisionId", revision, "runType", "test"))).path("flowRunId").asText();

        JsonNode finished = data(post("/flow-runs/" + runId + "/finish"), "{\"outputPayload\":" + PAYLOAD + "}");
        assertNumbers(finished.path("outputPayload"));
        assertNumbers(data(get("/flow-runs/" + runId), null).path("outputPayload"));
        assertEquals(sharedMapperUsesBigDecimals, mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        assertEquals(ordinaryJsonBefore, mapper.readTree(PAYLOAD), "Exact flow JSON must not reconfigure the shared mapper");
    }

    @ParameterizedTest(name = "finish null compatibility: {0}")
    @MethodSource("finishNullBodies")
    void finishingKeepsExistingNullAndMissingBodySemantics(String name, String body, int expectedStatus,
        boolean expectedSqlNull) throws Exception {
        String workflow = workflow();
        String revision = data(post("/workflows/" + workflow + "/revisions"), null)
            .path("workflowRevisionId").asText();
        String runId = data(post("/flow-runs"), mapper.writeValueAsString(Map.of("workflowId", workflow,
            "workflowRevisionId", revision, "runType", "test"))).path("flowRunId").asText();
        MockHttpServletRequestBuilder request = post("/flow-runs/" + runId + "/finish")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(body);
        mockMvc.perform(request).andExpect(status().is(expectedStatus));

        assertEquals(expectedSqlNull, jdbcTemplate.queryForObject(
            "SELECT output_payload IS NULL FROM flow_run WHERE flow_run_id = ?", Boolean.class, runId));
        JsonNode run = data(get("/flow-runs/" + runId), null);
        assertEquals(expectedStatus == 200 ? "finished" : "running", run.path("status").asText());
        assertTrue(run.path("outputPayload").isNull());
        assertTrue(run.has("outputPayloadJson"));
        if (expectedSqlNull) {
            assertTrue(run.path("outputPayloadJson").isNull());
        } else {
            assertEquals("null", run.path("outputPayloadJson").textValue());
        }
        if (!expectedSqlNull) {
            assertEquals("null", jdbcTemplate.queryForObject(
                "SELECT output_payload::text FROM flow_run WHERE flow_run_id = ?", String.class, runId));
        }
    }

    private static Stream<Arguments> finishNullBodies() {
        return Stream.of(
            Arguments.of("omitted outputPayload", "{}", 200, true),
            Arguments.of("explicit JSON null", "{\"outputPayload\":null}", 200, false),
            Arguments.of("JSON null request", "null", 400, true),
            Arguments.of("absent request body", null, 400, true));
    }

    private String workflow() throws Exception {
        return data(post("/workflows"), mapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Exact JSON " + UUID.randomUUID()))).path("workflowId").asText();
    }

    private String process(String workflow, String name) throws Exception {
        return data(post("/processes"), mapper.writeValueAsString(Map.of("workflowId", workflow,
            "processName", name))).path("processId").asText();
    }

    private static JsonNode findStep(JsonNode steps, String nodeId) {
        for (JsonNode step : steps) {
            if (nodeId.equals(step.path("nodeId").asText())) return step;
        }
        fail("Expected a downstream step for " + nodeId);
        return null;
    }

    private void assertExactPayload(JsonNode response, String field) throws Exception {
        assertTrue(response.path(field + "Json").isTextual(), field + "Json must be an exact JSON string");
        JsonNode exact = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(response.path(field + "Json").textValue());
        assertNumbers(exact);
        assertTrue(exact.path("nested").get(0).isNull());
        assertEquals("quoted \"value\"\nnext", exact.path("nested").get(1).path("text").textValue());
        assertNumbers(response.path(field));
    }

    private static void assertNumbers(JsonNode payload) {
        assertEquals(new BigInteger("9007199254740993"), payload.path("integer").bigIntegerValue());
        assertEquals(0, new BigDecimal("9007199254740993.123456789").compareTo(payload.path("decimal").decimalValue()));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response).path("data");
    }
}
