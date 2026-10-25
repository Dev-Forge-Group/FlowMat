package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class FlowRunPortContractsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absentPortsOrContractsReturnBeforeInspectingTheirPayloads() {
        FlowRunPortContracts contracts = new FlowRunPortContracts(
            Map.of("rule-only", ConditionExpression.compile("quantity > 0")),
            Map.of("schema-only", new PortSchema(Map.of("grade", "string"), Set.of("grade"))));

        assertDoesNotThrow(() -> contracts.requireSchema(null, null));
        assertDoesNotThrow(() -> contracts.requireRule(null, null));
        assertDoesNotThrow(() -> contracts.requireSchema("unknown", null));
        assertDoesNotThrow(() -> contracts.requireRule("unknown", null));
        assertDoesNotThrow(() -> contracts.requireSchema("rule-only", null));
        assertDoesNotThrow(() -> contracts.requireRule("schema-only", null));
    }

    @Test
    void missingOptionalAttributesPassButExplicitNonobjectAttributesFail() throws Exception {
        FlowRunPortContracts contracts = schema(Map.of("grade", "string"), Set.of());

        assertDoesNotThrow(() -> contracts.requireSchema("port", json("{}")));
        assertDoesNotThrow(() -> contracts.requireSchema("port", json("{\"attrs\":{}}")));
        for (String attrs : List.of("null", "[]", "false", "1", "\"text\"")) {
            JsonNode output = json("{\"attrs\":" + attrs + "}");
            assertConflict(() -> contracts.requireSchema("port", output),
                "Port port outputSnapshot.attrs must be an object.");
        }
    }

    @Test
    void requiredAttributesRejectMissingAndNullBeforeCheckingPropertyTypes() throws Exception {
        FlowRunPortContracts contracts = schema(Map.of("grade", "string", "count", "number"), Set.of("grade"));

        for (String output : List.of("{}", "{\"attrs\":{\"count\":\"wrong\"}}",
            "{\"attrs\":{\"grade\":null,\"count\":\"wrong\"}}")) {
            assertConflict(() -> contracts.requireSchema("port", json(output)),
                "Port port requires outputSnapshot.attrs.grade.");
        }
        assertConflict(() -> contracts.requireSchema("port", json("{\"attrs\":{\"grade\":false}}")),
            "Port port outputSnapshot.attrs.grade must be string.");
    }

    @Test
    void nativeTypesAllowEmptyStringZeroFalseAndUndeclaredAttributesWithoutChangingInput() throws Exception {
        Map<String, PortSchema> schemas = new HashMap<>();
        schemas.put("port", new PortSchema(Map.of("grade", "string", "count", "number", "accepted", "boolean"),
            Set.of("grade", "count", "accepted")));
        Map<String, PortSchema> beforeSchemas = new HashMap<>(schemas);
        FlowRunPortContracts contracts = new FlowRunPortContracts(Map.of(), schemas);
        JsonNode output = json("""
            {"quantity":123456789.123456789,"attrs":{"grade":"","count":0,"accepted":false,"extra":{"any":null}}}
            """);
        JsonNode before = output.deepCopy();

        contracts.requireSchema("port", output);

        assertEquals(before, output);
        assertEquals(beforeSchemas, schemas);
    }

    @ParameterizedTest
    @MethodSource("wrongAttributeTypes")
    void optionalPropertiesRejectNullAndOtherJsonTypesWithoutCoercion(String type, String value) throws Exception {
        FlowRunPortContracts contracts = schema(Map.of("value", type), Set.of());
        assertConflict(() -> contracts.requireSchema("port", json("{\"attrs\":{\"value\":" + value + "}}")),
            "Port port outputSnapshot.attrs.value must be " + type + ".");
    }

    private static Stream<Arguments> wrongAttributeTypes() {
        return Stream.of(Arguments.of("string", "1"), Arguments.of("string", "null"),
            Arguments.of("number", "\"1\""), Arguments.of("number", "true"),
            Arguments.of("boolean", "\"true\""), Arguments.of("boolean", "0"));
    }

    @Test
    void rulesUseExactDecimalAndNestedValuesWithoutChangingTheMaps() {
        ConditionExpression rule = ConditionExpression.compile("quantity > 1 and attrs.grade = 'A' and attrs.accepted = true");
        Map<String, ConditionExpression> rules = new HashMap<>(Map.of("port", rule));
        Map<String, ConditionExpression> beforeRules = new HashMap<>(rules);
        Map<String, Object> values = Map.of("quantity", new BigDecimal("1.0000000000000000001"),
            "attrs", Map.of("grade", "A", "accepted", true));
        FlowRunPortContracts contracts = new FlowRunPortContracts(rules, Map.of());

        assertDoesNotThrow(() -> contracts.requireRule("port", values));

        assertEquals(new BigDecimal("1.0000000000000000001"), values.get("quantity"));
        assertEquals(Map.of("grade", "A", "accepted", true), values.get("attrs"));
        assertEquals(beforeRules, rules);
        assertConflict(() -> contracts.requireRule("port", Map.of("quantity", BigDecimal.ONE)),
            "Port port validationRule did not pass.");
    }

    @Test
    void nullAndMissingRuleValuesRetainTheEvaluationFailure() {
        FlowRunPortContracts contracts = new FlowRunPortContracts(
            Map.of("port", ConditionExpression.compile("quantity > 0")), Map.of());

        assertConflict(() -> contracts.requireRule("port", null), "Port port validationRule did not pass.");
        assertConflict(() -> contracts.requireRule("port", Map.of()), "Port port validationRule did not pass.");
    }

    @Test
    void graphPreservesCapacityUnitBothSchemasThenBothRulesPrecedence() throws Exception {
        FlowRunGraph graph = FlowRunGraph.from(json("""
            {"processes":[{"processId":"source"},{"processId":"target"}],
             "processIos":[
               {"processIoId":"out","processId":"source","direction":"output","validationRule":"quantity >= 3",
                 "schemaJson":{"type":"object","properties":{"sent":{"type":"string"}},"required":["sent"]}},
               {"processIoId":"in","processId":"target","direction":"input","validationRule":"quantity >= 4",
                 "schemaJson":{"type":"object","properties":{"received":{"type":"string"}},"required":["received"]}}],
             "connections":[{"connectionId":"edge","fromProcessId":"source","toProcessId":"target",
               "fromIoId":"out","toIoId":"in","capacity":5,"unit":"kg"}]}
            """));
        ObjectNode output = (ObjectNode) json("{\"quantity\":6,\"unit\":\"ea\",\"attrs\":{}}");
        assertConflict(() -> graph.routes("source", output), "Connection edge capacity exceeded.");
        output.put("quantity", 2);
        assertConflict(() -> graph.routes("source", output), "Connection edge unit does not match outputSnapshot.unit.");
        output.put("unit", "kg");
        assertConflict(() -> graph.routes("source", output), "Port out requires outputSnapshot.attrs.sent.");
        ((ObjectNode) output.path("attrs")).put("sent", "A");
        assertConflict(() -> graph.routes("source", output), "Port in requires outputSnapshot.attrs.received.");
        ((ObjectNode) output.path("attrs")).put("received", "A");
        assertConflict(() -> graph.routes("source", output), "Port out validationRule did not pass.");
        output.put("quantity", 3);
        assertConflict(() -> graph.routes("source", output), "Port in validationRule did not pass.");
        output.put("quantity", 4);
        assertEquals(List.of(new FlowRunGraph.Route("edge", "target")), graph.routes("source", output));
    }

    @Test
    void graphOutputRuleSeesSentQuantityAndInputRuleSeesArrivingQuantity() throws Exception {
        FlowRunGraph graph = FlowRunGraph.from(json("""
            {"schemaVersion":2,"processes":[{"processId":"source"},{"processId":"target"}],
             "processIos":[
               {"processIoId":"out","processId":"source","direction":"output","validationRule":"quantity >= 100"},
               {"processIoId":"in","processId":"target","direction":"input","validationRule":"quantity < 99"}],
             "connections":[{"connectionId":"edge","fromProcessId":"source","toProcessId":"target",
               "fromIoId":"out","toIoId":"in","lossRate":0.02}]}
            """));

        FlowRunGraph.Decision decision = graph.preview("source", json("{\"quantity\":100}")).get(0);

        assertEquals(new BigDecimal("98.0000"), decision.quantity());
        assertEquals(new FlowRunGraph.Route("edge", "target"),
            graph.routes("source", json("{\"quantity\":100}")).get(0));
    }

    @Test
    void graphCompleteLossStillChecksOutputSchemaAndRuleAndSkipsBothInputContracts() throws Exception {
        FlowRunGraph graph = FlowRunGraph.from(json("""
            {"schemaVersion":2,"processes":[{"processId":"source"},{"processId":"target"}],
             "processIos":[
               {"processIoId":"out","processId":"source","direction":"output","validationRule":"attrs.accepted = true",
                 "schemaJson":{"type":"object","properties":{"grade":{"type":"string"}},"required":["grade"]}},
               {"processIoId":"in","processId":"target","direction":"input","validationRule":"quantity > 1",
                 "schemaJson":{"type":"object","properties":{"needed":{"type":"string"}},"required":["needed"]}}],
             "connections":[{"connectionId":"edge","fromProcessId":"source","toProcessId":"target",
               "fromIoId":"out","toIoId":"in","lossRate":0.6}]}
            """));
        ObjectNode output = (ObjectNode) json("{\"quantity\":0.0001,\"attrs\":{}}");
        assertConflict(() -> graph.preview("source", output), "Port out requires outputSnapshot.attrs.grade.");
        ((ObjectNode) output.path("attrs")).put("grade", "A");
        assertConflict(() -> graph.preview("source", output), "Port out validationRule did not pass.");
        ((ObjectNode) output.path("attrs")).put("accepted", true);

        FlowRunGraph.Decision decision = graph.preview("source", output).get(0);

        assertEquals("loss_empty", decision.reason());
        assertEquals(false, decision.willRoute());
        assertEquals(new BigDecimal("0.0000"), decision.quantity());
    }

    private FlowRunPortContracts schema(Map<String, String> properties, Set<String> required) {
        return new FlowRunPortContracts(Map.of(), Map.of("port", new PortSchema(properties, required)));
    }

    private void assertConflict(Executable action, String message) {
        BusinessException failure = assertThrows(BusinessException.class, action);
        assertEquals(ErrorCode.CONFLICT, failure.getErrorCode());
        assertEquals(message, failure.getMessage());
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
