package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class WorkInstructionInputValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ProductionRunRepository runRepository;

    static String[] emptyAfterTrim() {
        return new String[] {"\u0000", "\u0001", "\u001f", " \u0001\t"};
    }

    static Stream<Arguments> invalidInstructionText() {
        return Stream.of(Arguments.of("title", "Original\u0000"),
            Arguments.of("body", "bad\u0000body"),
            Arguments.of("documentUrl", "https://example.org/path\ud800"));
    }

    static Stream<Arguments> invalidStepText() {
        return Stream.of(Arguments.of("text", "bad\ud800text"),
            Arguments.of("valueLabel", "label\u0000"));
    }

    static Stream<Arguments> invalidConfirmationText() {
        return Stream.of(Arguments.of("value", "bad\u0000value"),
            Arguments.of("note", "bad\udc00note"));
    }

    @ParameterizedTest
    @MethodSource("emptyAfterTrim")
    void anEmptyNormalizedTitleCannotCreateAnInstruction(String title) throws Exception {
        String item = item();
        call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT, "itemId", item, "title", title))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("title")));
        call(get("/work-instructions").param("projectId", DEMO_PROJECT).param("itemId", item))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @MethodSource("emptyAfterTrim")
    void anEmptyNormalizedStepCannotBeAdded(String text) throws Exception {
        String instruction = instruction(item());
        call(post("/work-instructions/" + instruction + "/steps"), Map.of("text", text))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("text")));
        assertNoSteps(instruction);
    }

    @ParameterizedTest
    @MethodSource("emptyAfterTrim")
    void anEmptyNormalizedValueCannotConfirmTheStepOrUnlockFinishing(String value) throws Exception {
        Fixture fixture = releasedRun();
        call(check(fixture), Map.of("value", value))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("value")));
        assertUnchecked(fixture);
        call(post("/production-runs/" + fixture.run() + "/finish"), Map.of("actualOutputQty", 1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message", containsString("0 of 1")));
    }

    @ParameterizedTest
    @MethodSource("invalidInstructionText")
    void creationRejectsUnstorableInstructionText(String field, String value) throws Exception {
        String item = item();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "title", "Original title", "body", "Original body", "documentUrl", "https://example.org/original"));
        body.put(field, value);
        call(post("/work-instructions"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/work-instructions").param("projectId", DEMO_PROJECT).param("itemId", item))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @ParameterizedTest
    @MethodSource("invalidInstructionText")
    void rejectedUpdatesKeepTheOriginalInstruction(String field, String value) throws Exception {
        String instruction = instruction(item());
        Map<String, Object> body = new LinkedHashMap<>(Map.of("title", "Original title", "body", "Changed body",
            "documentUrl", "https://example.org/changed"));
        body.put(field, value);
        call(put("/work-instructions/" + instruction), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        JsonNode original = savedInstruction(instruction);
        assertEquals("Original title", original.path("title").asText());
        assertEquals("Original body", original.path("body").asText());
        assertEquals("https://example.org/original", original.path("documentUrl").asText());
    }

    @ParameterizedTest
    @MethodSource("invalidStepText")
    void rejectedStepTextDoesNotAddAStep(String field, String value) throws Exception {
        String instruction = instruction(item());
        Map<String, Object> body = new LinkedHashMap<>(Map.of("text", "Record value", "recordsValue", true,
            "valueLabel", "Measured value"));
        body.put(field, value);
        call(post("/work-instructions/" + instruction + "/steps"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        assertNoSteps(instruction);
    }

    @ParameterizedTest
    @MethodSource("invalidConfirmationText")
    void rejectedConfirmationDoesNotPinARevisionOrCreateACheck(String field, String value) throws Exception {
        Fixture fixture = releasedRun();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("value", "Valid value", "note", "Valid note"));
        body.put(field, value);
        call(check(fixture), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        assertUnchecked(fixture);
    }

    @Test
    void supportedUnicodeAndInternalControlCharactersRemainStorable() throws Exception {
        String item = item();
        String instruction = data(call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT,
            "itemId", item, "title", " 온도 😀 ", "body", " 본문\t😀 ", "blocksFinish", true)))
            .path("instructionId").asText();
        JsonNode original = savedInstruction(instruction);
        assertEquals("온도 😀", original.path("title").asText());
        assertEquals("본문\t😀", original.path("body").asText());
        String step = data(call(post("/work-instructions/" + instruction + "/steps"),
            Map.of("text", " 확인 😀 ", "recordsValue", true, "valueLabel", " 온도 °C 😀 ")))
            .path("steps").get(0).path("stepId").asText();
        call(post("/work-instructions/" + instruction + "/release")).andExpect(status().isOk());
        String run = run(item);
        call(check(new Fixture(instruction, run, step)), Map.of("value", " 220℃😀\u0001value ", "note", "\t기록 😀\n주석 "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks[0].value").value("220℃😀\u0001value"))
            .andExpect(jsonPath("$.data.checks[0].note").value("기록 😀\n주석"));
        call(post("/production-runs/" + run + "/finish"), Map.of("actualOutputQty", 1))
            .andExpect(status().isOk());
    }

    private Fixture releasedRun() throws Exception {
        String item = item();
        String instruction = instruction(item);
        String step = data(call(post("/work-instructions/" + instruction + "/steps"),
            Map.of("text", "Record value", "recordsValue", true))).path("steps").get(0).path("stepId").asText();
        call(post("/work-instructions/" + instruction + "/release")).andExpect(status().isOk());
        return new Fixture(instruction, run(item), step);
    }

    private void assertNoSteps(String instruction) throws Exception {
        assertEquals(0, savedInstruction(instruction).path("steps").size());
    }

    private JsonNode savedInstruction(String instruction) throws Exception {
        for (JsonNode candidate : data(call(get("/work-instructions").param("projectId", DEMO_PROJECT)))) {
            if (candidate.path("instructionId").asText().equals(instruction)) return candidate;
        }
        throw new AssertionError("The instruction must remain readable in its project's list.");
    }

    private void assertUnchecked(Fixture fixture) throws Exception {
        call(get("/production-runs/" + fixture.run() + "/instruction")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checks.length()").value(0))
            .andExpect(jsonPath("$.data.requiredDone").value(0));
        assertNull(runRepository.findById(fixture.run()).orElseThrow().getWorkInstructionId());
    }

    private String instruction(String item) throws Exception {
        return data(call(post("/work-instructions"), Map.of("projectId", DEMO_PROJECT, "itemId", item,
            "title", "Original title", "body", "Original body", "documentUrl", "https://example.org/original",
            "blocksFinish", true))).path("instructionId").asText();
    }

    private String item() throws Exception {
        return data(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "WI-" + UUID.randomUUID(),
            "itemName", "Instruction input", "itemType", "product", "unitId", "unit_ea")))
            .path("itemId").asText();
    }

    private String run(String item) throws Exception {
        return data(call(post("/production-runs/start"), Map.of("projectId", DEMO_PROJECT, "workflowId", DEMO_WORKFLOW,
            "targetItemId", item, "plannedOutputQty", 1))).path("productionRunId").asText();
    }

    private MockHttpServletRequestBuilder check(Fixture fixture) {
        return post("/production-runs/" + fixture.run() + "/instruction/steps/" + fixture.step() + "/check");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private record Fixture(String instruction, String run, String step) {}
}
