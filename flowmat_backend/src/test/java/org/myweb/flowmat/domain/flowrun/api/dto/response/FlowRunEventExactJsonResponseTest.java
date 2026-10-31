package org.myweb.flowmat.domain.flowrun.api.dto.response;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FlowRunEventExactJsonResponseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @ParameterizedTest(name = "event payload: {0}")
    @MethodSource("jsonValues")
    void eventWireAddsAnExactJsonStringWithoutChangingPayloadOrMetadata(String name, String json) throws Exception {
        JsonNode payload = json == null ? null : MAPPER.readTree(json);
        var response = new FlowRunEventResponse("event", "run", "step", "step_completed", payload,
            "request", null, "user", "owner");
        JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(response));

        assertTrue(wire.has("payload"), "The existing payload field must remain present");
        assertTrue(wire.has("payloadJson"), "payloadJson must be present on the wire");
        if (payload == null) {
            assertTrue(wire.path("payload").isNull());
            assertTrue(wire.path("payloadJson").isNull(), "An absent payload must not become the text null");
        } else {
            assertEquals(payload, wire.path("payload"));
            assertTrue(wire.path("payloadJson").isTextual());
            assertEquals(payload.toString(), wire.path("payloadJson").textValue());
            assertEquals(payload, MAPPER.readTree(wire.path("payloadJson").textValue()));
        }
        assertEquals("event", wire.path("eventId").textValue());
        assertEquals("run", wire.path("flowRunId").textValue());
        assertEquals("step", wire.path("stepId").textValue());
        assertEquals("step_completed", wire.path("eventType").textValue());
        assertEquals("request", wire.path("requestId").textValue());
        assertTrue(wire.path("occurredAt").isNull());
        assertEquals("user", wire.path("actorType").textValue());
        assertEquals("owner", wire.path("actorId").textValue());
    }

    private static Stream<Arguments> jsonValues() {
        return Stream.of(
            Arguments.of("large integer", "9007199254740993"),
            Arguments.of("long decimal", "9007199254740993.123456789123456789"),
            Arguments.of("positive exponent", "9.007199254740993123456789E+40"),
            Arguments.of("negative exponent", "1.23456789123456789E-40"),
            Arguments.of("nested values", "{\"rows\":[9007199254740995,9007199254740993.123456789,null,{\"ok\":true}]}"),
            Arguments.of("boolean scalar", "false"),
            Arguments.of("explicit JSON null", "null"),
            Arguments.of("absent payload", (String) null),
            Arguments.of("escaped string scalar", "\"quoted \\\"value\\\"\\nnext\\tline\\\\path\""));
    }
}
