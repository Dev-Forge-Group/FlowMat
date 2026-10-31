package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class FlowRunEventExactJsonIntegrationTest extends IntegrationTestSupport {
    private static final String INPUT = "{\"integer\":9007199254740993,"
        + "\"decimal\":9007199254740993.123456789,\"exponent\":1.23456789123456789E-20,"
        + "\"rows\":[null,{\"text\":\"quoted \\\"value\\\"\\nnext\"}]}";
    private static final String OUTPUT = "{\"integer\":9007199254740995,"
        + "\"decimal\":9007199254740995.987654321,\"exponent\":9.87654321987654321E+20,"
        + "\"rows\":[false,{\"text\":\"output\\tvalue\\\\path\"}]}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0} run event JSON remains exact")
    @ValueSource(strings = {"manual", "graph"})
    void eventsExposeExactInputAndOutputWithoutChangingPublishedOrRecordedEvidence(String mode) throws Exception {
        String workflow = data(post("/workflows"), mapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Event JSON " + UUID.randomUUID())), "workflow").path("workflowId").asText();
        String process = data(post("/processes"), mapper.writeValueAsString(Map.of("workflowId", workflow,
            "processName", "Event JSON source")), "process").path("processId").asText();
        String revision = data(post("/workflows/" + workflow + "/revisions"), null, "publish")
            .path("workflowRevisionId").asText();
        String publishedSnapshot = jdbc.queryForObject(
            "select snapshot_json::text from workflow_revision where workflow_revision_id=?", String.class, revision);

        String requestPrefix = "event-json-" + UUID.randomUUID();
        String path = "graph".equals(mode) ? "/flow-runs/graph" : "/flow-runs";
        String runId = data(post(path), "{\"workflowId\":\"" + workflow + "\",\"workflowRevisionId\":\""
            + revision + "\",\"runType\":\"test\",\"inputPayload\":" + INPUT + "}", requestPrefix + "-run")
            .path("flowRunId").asText();
        String stepId;
        if ("graph".equals(mode)) {
            JsonNode roots = data(get("/flow-runs/" + runId + "/steps"), null, "roots");
            assertEquals(1, roots.size());
            stepId = roots.get(0).path("stepId").asText();
        } else {
            stepId = data(post("/flow-runs/" + runId + "/steps"), "{\"nodeId\":\"" + process
                + "\",\"inputSnapshot\":" + INPUT + "}", requestPrefix + "-create").path("stepId").asText();
        }
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/start"), null, requestPrefix + "-start");
        data(post("/flow-runs/" + runId + "/steps/" + stepId + "/complete"), "{\"outputSnapshot\":" + OUTPUT + "}",
            requestPrefix + "-complete");
        data(post("/flow-runs/" + runId + "/finish"), "{\"outputPayload\":" + OUTPUT + "}", requestPrefix + "-finish");

        var evidenceBefore = jdbc.queryForList(
            "select * from flow_run_event where flow_run_id=? order by event_id", runId);
        JsonNode events = data(get("/flow-runs/" + runId + "/events"), null, requestPrefix + "-read");
        assertEvent(events, "run_started", runId, null, INPUT, requestPrefix + "-run");
        assertEvent(events, "step_created", runId, stepId, INPUT,
            requestPrefix + ("graph".equals(mode) ? "-run" : "-create"));
        assertEvent(events, "step_completed", runId, stepId, OUTPUT, requestPrefix + "-complete");
        assertEvent(events, "run_finished", runId, null, OUTPUT, requestPrefix + "-finish");

        assertEquals(events, data(get("/flow-runs/" + runId + "/events"), null, requestPrefix + "-read-again"));
        assertEquals(evidenceBefore, jdbc.queryForList(
            "select * from flow_run_event where flow_run_id=? order by event_id", runId), "Reading events must not rewrite evidence");
        assertEquals(publishedSnapshot, jdbc.queryForObject(
            "select snapshot_json::text from workflow_revision where workflow_revision_id=?", String.class, revision));
        assertExactValue(exact(INPUT), exact(jdbc.queryForObject(
            "select input_payload::text from flow_run where flow_run_id=?", String.class, runId)));
        assertExactValue(exact(OUTPUT), exact(jdbc.queryForObject(
            "select output_payload::text from flow_run where flow_run_id=?", String.class, runId)));
        assertExactValue(exact(INPUT), exact(jdbc.queryForObject(
            "select input_snapshot::text from flow_run_step where step_id=?", String.class, stepId)));
        assertExactValue(exact(OUTPUT), exact(jdbc.queryForObject(
            "select output_snapshot::text from flow_run_step where step_id=?", String.class, stepId)));
    }

    private void assertEvent(JsonNode events, String type, String runId, String stepId, String expectedJson,
        String requestId) throws Exception {
        JsonNode found = null;
        for (JsonNode event : events) {
            if (type.equals(event.path("eventType").textValue())) {
                assertNull(found, "Expected one " + type + " event");
                found = event;
            }
        }
        assertNotNull(found, "Missing " + type + " event");
        assertTrue(found.path("payloadJson").isTextual(), type + " must provide exact payloadJson");
        String persisted = jdbc.queryForObject("select payload_json::text from flow_run_event where event_id=?",
            String.class, found.path("eventId").textValue());
        assertEquals(exact(persisted).toString(), found.path("payloadJson").textValue(),
            "Exact JSON text must serialize the persisted evidence rather than recreate the original request formatting");
        assertExactValue(exact(expectedJson), exact(found.path("payloadJson").textValue()));
        assertExactValue(exact(expectedJson), found.path("payload"));
        assertEquals(runId, found.path("flowRunId").textValue());
        if (stepId == null) assertTrue(found.path("stepId").isNull());
        else assertEquals(stepId, found.path("stepId").textValue());
        assertFalse(found.path("eventId").asText().isBlank());
        assertEquals(requestId, found.path("requestId").textValue());
        assertEquals("user", found.path("actorType").textValue());
        assertEquals(DEMO_OWNER, found.path("actorId").textValue());
        assertDoesNotThrow(() -> OffsetDateTime.parse(foundTime(events, type)));
    }

    private static String foundTime(JsonNode events, String type) {
        for (JsonNode event : events) if (type.equals(event.path("eventType").textValue())) return event.path("occurredAt").asText();
        return "";
    }

    private JsonNode exact(String json) throws Exception {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(json);
    }

    /** JSONB can expand exponents or reorder object fields; neither may change any numeric value or other content. */
    private static void assertExactValue(JsonNode expected, JsonNode actual) {
        assertNotNull(actual);
        if (expected.isNumber()) {
            assertTrue(actual.isNumber(), "Expected a number: " + expected);
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), "Numeric precision changed");
        } else if (expected.isObject()) {
            assertTrue(actual.isObject());
            assertEquals(expected.size(), actual.size());
            expected.fields().forEachRemaining(field -> {
                assertTrue(actual.has(field.getKey()), "Missing field: " + field.getKey());
                assertExactValue(field.getValue(), actual.get(field.getKey()));
            });
        } else if (expected.isArray()) {
            assertTrue(actual.isArray());
            assertEquals(expected.size(), actual.size());
            for (int index = 0; index < expected.size(); index++) assertExactValue(expected.get(index), actual.get(index));
        } else {
            assertEquals(expected, actual);
        }
    }

    private JsonNode data(MockHttpServletRequestBuilder request, String body, String requestId) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .header("X-Request-Id", requestId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return exact(response).path("data");
    }
}
