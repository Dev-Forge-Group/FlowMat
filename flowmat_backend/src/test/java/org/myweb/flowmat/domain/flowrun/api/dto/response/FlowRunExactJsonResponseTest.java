package org.myweb.flowmat.domain.flowrun.api.dto.response;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FlowRunExactJsonResponseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @ParameterizedTest(name = "run payloads: {0}")
    @MethodSource("jsonValues")
    void runPayloadWireAddsExactStringsWithoutChangingTheOriginalFields(String name, String json) throws Exception {
        JsonNode value = json == null ? null : MAPPER.readTree(json);
        var response = new FlowRunResponse("run", "project", "workflow", "revision", null, "test", "graph",
            "running", value, value, null, null, "owner");
        JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(response));

        assertExactField(wire, "inputPayload", value);
        assertExactField(wire, "outputPayload", value);
        assertEquals("run", wire.path("flowRunId").textValue());
    }

    @ParameterizedTest(name = "step snapshots: {0}")
    @MethodSource("jsonValues")
    void stepSnapshotWireAddsExactStringsWithoutChangingTheOriginalFields(String name, String json) throws Exception {
        JsonNode value = json == null ? null : MAPPER.readTree(json);
        var response = new FlowRunStepResponse("step", "run", "node", null, null, "running", 1, null, null,
            null, value, value, null, null, null);
        JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(response));

        assertExactField(wire, "inputSnapshot", value);
        assertExactField(wire, "outputSnapshot", value);
        assertEquals("step", wire.path("stepId").textValue());
    }

    private static void assertExactField(JsonNode wire, String field, JsonNode value) throws Exception {
        assertTrue(wire.has(field), "The existing " + field + " field must remain present");
        assertTrue(wire.has(field + "Json"), field + "Json must be present on the wire");
        if (value == null) {
            assertTrue(wire.path(field).isNull());
            assertTrue(wire.path(field + "Json").isNull(), "An absent payload must not become the text null");
        } else {
            assertTrue(wire.path(field + "Json").isTextual(), field + "Json must be a JSON string");
            assertEquals(value.toString(), wire.path(field + "Json").textValue());
            assertEquals(value, wire.path(field));
            assertEquals(value, MAPPER.readTree(wire.path(field + "Json").textValue()));
        }
    }

    private static Stream<Arguments> jsonValues() {
        return Stream.of(
            Arguments.of("large integer", "9007199254740993"),
            Arguments.of("large decimal", "9007199254740993.123456789"),
            Arguments.of("nested values", "{\"rows\":[9007199254740995,9007199254740993.123456789,null,{\"ok\":true}]}"),
            Arguments.of("boolean scalar", "false"),
            Arguments.of("explicit JSON null", "null"),
            Arguments.of("absent payload", (String) null),
            Arguments.of("escaped string scalar", "\"quoted \\\"value\\\"\\nnext\\tline\\\\path\""));
    }
}
