package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class RunStateSnapshotValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"{", "not-json", "{} []", "[1,]", "{\"state\":1} garbage"})
    void malformedDataIsRejectedBeforeSaving(String snapshotData) throws Exception {
        String runId = run();
        call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", snapshotData))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("snapshotData")));
        assertEmpty(runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\\u0000\"", "{\"\\u0000\":1}", "\"\\ud800\"", "\"\\udc00\"",
        "{\"n\":1e131072}", "1e-16384", "1e2147483648",
        "{\"a\":\"\\u0000\",\"a\":1}", "{\"a\":1e131072,\"a\":1}"})
    void dataThatJsonbCannotStoreIsRejectedBeforeSaving(String snapshotData) throws Exception {
        String runId = run();
        call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", snapshotData))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("snapshotData")));
        assertEmpty(runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshotName", "snapshotType", "note"})
    void unsupportedMetadataCharactersAreRejected(String field) throws Exception {
        String runId = run();
        call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", "{}", field, "bad\u0000text"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        assertEmpty(runId);
    }

    static Stream<Arguments> unstorableMetadataBeforeNormalization() {
        return Stream.of("snapshotName", "snapshotType", "note").flatMap(field ->
            Stream.of("\u0000original", "original\u0000", "\u0000", "bad\ud800text", "bad\udc00text")
                .map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("unstorableMetadataBeforeNormalization")
    void originalMetadataIsValidatedBeforeWhitespaceNormalization(String field, String value) throws Exception {
        String runId = run();
        call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", "{}", field, value))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        assertEmpty(runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e131071", "1e-16383", "0e131072"})
    void jsonbNumericBoundariesRemainStorable(String snapshotData) throws Exception {
        String runId = run();
        String snapshotId = data(call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", snapshotData)))
            .path("runStateSnapshotId").asText();
        assertEquals(true, jdbcTemplate.queryForObject(
            "select snapshot_data = cast(? as jsonb) from run_state_snapshot where run_state_snapshot_id = ?",
            Boolean.class, snapshotData, snapshotId));
    }

    @ParameterizedTest
    @CsvSource({"snapshotName,101", "snapshotType,31"})
    void metadataOverTheColumnLimitIsRejected(String field, int length) throws Exception {
        String runId = run();
        call(post("/run-state-snapshots"), Map.of("productionRunId", runId, "snapshotData", "{}", field, "x".repeat(length)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(field)));
        assertEmpty(runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[1,true]", "\"text\"", "2.5", "true", "null", "{\"emoji\":\"\\ud83e\\uddf1\"}"})
    void validJsonValuesRoundTripAndTheAuthenticatedUserIsRecorded(String snapshotData) throws Exception {
        String runId = run();
        String snapshotId = data(call(post("/run-state-snapshots"), Map.of("productionRunId", runId,
            "snapshotData", "  " + snapshotData + "  ", "createdBy", "untrusted-author")))
            .path("runStateSnapshotId").asText();
        JsonNode stored = data(call(get("/run-state-snapshots/" + snapshotId)));
        assertEquals(objectMapper.readTree(snapshotData), objectMapper.readTree(stored.path("snapshotData").asText()));
        assertEquals(DEMO_OWNER, stored.path("createdBy").asText());
        assertEquals("manual", stored.path("snapshotType").asText());
    }

    @Test
    void metadataUsesTrimmedUnicodeCharacterLimitsAndKeepsCustomTypes() throws Exception {
        String runId = run();
        String name = "\ud83e\uddf1".repeat(100);
        String type = "X".repeat(30);
        String snapshotId = data(call(post("/run-state-snapshots"), Map.of("productionRunId", runId,
            "snapshotData", "{}", "snapshotName", "  " + name + "  ", "snapshotType", " " + type + " ")))
            .path("runStateSnapshotId").asText();
        call(get("/run-state-snapshots/" + snapshotId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.snapshotName").value(name))
            .andExpect(jsonPath("$.data.snapshotType").value(type.toLowerCase(java.util.Locale.ROOT)));
    }

    private void assertEmpty(String runId) throws Exception {
        call(get("/run-state-snapshots").param("productionRunId", runId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(0)));
    }

    private String run() throws Exception {
        String workflowId = data(call(post("/workflows"), Map.of("projectId", DEMO_PROJECT,
            "workflowName", "Snapshot " + UUID.randomUUID()))).path("workflowId").asText();
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", workflowId,
            "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }
}
