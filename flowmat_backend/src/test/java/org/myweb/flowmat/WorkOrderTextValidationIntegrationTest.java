package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
class WorkOrderTextValidationIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("instruction", "bad\u0000instruction"),
            Arguments.of("assignedTo", DEMO_OWNER + "\u0000"),
            Arguments.of("instructionUrl", "https://example.org/path\ud800"));
    }

    @ParameterizedTest(name = "invalid metadata case {index}")
    @MethodSource("invalidFields")
    void rejectsUnstorableMetadataBeforeCreatingTheOrder(String field, String value) throws Exception {
        int before = count();
        Map<String, Object> body = original("Original " + UUID.randomUUID());
        body.put(field, value);
        call(post("/work-orders"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        assertEquals(before, count());
    }

    @ParameterizedTest(name = "invalid replacement metadata case {index}")
    @MethodSource("invalidFields")
    void rejectedUpdatesKeepAllOriginalFields(String field, String value) throws Exception {
        String title = "Original " + UUID.randomUUID();
        String order = data(call(post("/work-orders"), original(title))).path("workOrderId").asText();
        Map<String, Object> update = original("Changed title");
        update.put("instruction", "Changed instruction");
        update.put("instructionUrl", "https://example.org/changed");
        update.put(field, value);
        call(put("/work-orders/" + order), update).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderTitle").value(title))
            .andExpect(jsonPath("$.data.instruction").value("Original instruction"))
            .andExpect(jsonPath("$.data.assignedTo").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.instructionUrl").value("https://example.org/original"));
    }

    @Test
    void preservesSupportedUnicodeAndClearsOptionalFieldsAfterNormalization() throws Exception {
        Map<String, Object> body = original("Metadata " + UUID.randomUUID());
        body.put("instruction", " \t첫 줄 😀\u0001본문\n ");
        body.put("assignedTo", " " + DEMO_OWNER + " ");
        body.put("instructionUrl", " https://example.org/설명😀 ");
        String order = data(call(post("/work-orders"), body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instruction").value("첫 줄 😀\u0001본문"))
            .andExpect(jsonPath("$.data.assignedTo").value(DEMO_OWNER))
            .andExpect(jsonPath("$.data.instructionUrl").value("https://example.org/설명😀")))
            .path("workOrderId").asText();
        call(put("/work-orders/" + order), Map.of("targetQuantity", 1,
            "instruction", " \u0001\t", "assignedTo", " \u0001\t", "instructionUrl", " \u0001\t"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.instruction").value(nullValue()))
            .andExpect(jsonPath("$.data.assignedTo").value(nullValue()))
            .andExpect(jsonPath("$.data.instructionUrl").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "😀"})
    void assigneeLengthMatchesTheDatabaseForCreateAndUpdate(String character) throws Exception {
        String accepted = character.repeat(50);
        String rejected = character.repeat(51);
        String title = "Assignee boundary " + UUID.randomUUID();
        Map<String, Object> body = original(title);
        body.put("assignedTo", accepted);
        String order = data(call(post("/work-orders"), body)
            .andExpect(jsonPath("$.data.assignedTo").value(accepted))).path("workOrderId").asText();
        assertEquals(50, jdbcTemplate.queryForObject(
            "select char_length(assigned_to) from work_order where work_order_id = ?", Integer.class, order));

        int before = count();
        body.put("assignedTo", rejected);
        call(post("/work-orders"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("assignedTo")));
        assertEquals(before, count());
        body.put("workOrderTitle", "Rejected replacement");
        call(put("/work-orders/" + order), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString("assignedTo")));
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderTitle").value(title))
            .andExpect(jsonPath("$.data.assignedTo").value(accepted));

        String replacement = character.repeat(49) + "X";
        body.put("assignedTo", " " + replacement + " ");
        call(put("/work-orders/" + order), body).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignedTo").value(replacement));
    }

    private Map<String, Object> original(String title) {
        return new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "workOrderTitle", title,
            "targetQuantity", 1, "instruction", "Original instruction", "assignedTo", DEMO_OWNER,
            "instructionUrl", "https://example.org/original"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"projectId", "workflowId", "targetItemId", "bomId"})
    void invalidReferencesCannotCreateAnOrderOrAliasAValidReference(String field) throws Exception {
        Map<String, Object> body = original("Invalid reference " + UUID.randomUUID());
        body.put(field, reference(field) + "\u0000");
        int before = count();
        call(post("/work-orders"), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        assertEquals(before, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflowId", "targetItemId", "bomId"})
    void invalidReferenceUpdatesPreserveTheOrderAndItsOriginalReferences(String field) throws Exception {
        String title = "Original references " + UUID.randomUUID();
        Map<String, Object> body = original(title);
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", "itm_demo_mix_output");
        String order = data(call(post("/work-orders"), body)).path("workOrderId").asText();
        body.put("workOrderTitle", "Rejected replacement");
        body.put(field, reference(field) + "\u0000");
        call(put("/work-orders/" + order), body).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message", containsString(field)));
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workOrderTitle").value(title))
            .andExpect(jsonPath("$.data.workflowId").value(DEMO_WORKFLOW))
            .andExpect(jsonPath("$.data.targetItemId").value("itm_demo_mix_output"))
            .andExpect(jsonPath("$.data.bomId").value(nullValue()));
        body.remove("bomId");
        body.put("workflowId", " " + DEMO_WORKFLOW + " ");
        body.put("targetItemId", " itm_demo_mix_output ");
        call(put("/work-orders/" + order), body).andExpect(status().isOk());
    }

    @Test
    void optionalReferencesCanBeClearedWhenTrimmingLeavesNoText() throws Exception {
        Map<String, Object> body = original("Empty references " + UUID.randomUUID());
        for (String field : new String[] {"workflowId", "targetItemId", "bomId"}) body.put(field, " \u0001 ");
        String order = data(call(post("/work-orders"), body)).path("workOrderId").asText();
        body.put("workflowId", DEMO_WORKFLOW);
        body.put("targetItemId", "itm_demo_mix_output");
        body.remove("bomId");
        call(put("/work-orders/" + order), body).andExpect(status().isOk());
        for (String field : new String[] {"workflowId", "targetItemId", "bomId"}) body.put(field, " \u0001 ");
        call(put("/work-orders/" + order), body).andExpect(status().isOk());
        call(get("/work-orders/" + order)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.workflowId").value(nullValue()))
            .andExpect(jsonPath("$.data.targetItemId").value(nullValue()))
            .andExpect(jsonPath("$.data.bomId").value(nullValue()));
    }

    private String reference(String field) {
        return switch (field) {
            case "projectId" -> DEMO_PROJECT;
            case "workflowId" -> DEMO_WORKFLOW;
            case "targetItemId" -> "itm_demo_mix_output";
            case "bomId" -> "invalid-bom";
            default -> throw new IllegalArgumentException(field);
        };
    }

    private int count() {
        return jdbcTemplate.queryForObject("select count(*) from work_order where project_id = ?", Integer.class, DEMO_PROJECT);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(body)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
            .path("data");
    }
}
