package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class JsonMappingEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonMappingEngine engine = new JsonMappingEngine(mapper);

    @Test
    void constructsNestedObjectsFromPointersAndLiteralJsonWithoutCoercion() throws Exception {
        JsonNode source = json("{\"site_name\":\"Example\",\"enabled\":false,\"quads\":[[1,2],[3,4]],\"time\":\"2026-10-23T09:00:00+09:00\"}");
        JsonNode mapping = json("""
            {"object":{"site":{"object":{"name":{"from":"/site_name"},"enabled":{"from":"/enabled"}}},
              "geometry":{"from":"/quads"},"date":{"from":"/time"},"extra":{"literal":[null,true,1,"text"]}}}
            """);

        assertEquals(json("""
            {"site":{"name":"Example","enabled":false},"geometry":[[1,2],[3,4]],
             "date":"2026-10-23T09:00:00+09:00","extra":[null,true,1,"text"]}
            """), engine.map(source, mapping));
    }

    @Test
    void escapedPointersArrayIndicesAndTheEmptyMemberRemainDistinctFromTheRoot() throws Exception {
        JsonNode source = json("{\"a/b\":{\"~name\":[{\"\":7}]},\"\":null,\"01\":\"object key\"}");

        assertEquals(json("{\"number\":7,\"empty\":null,\"numericKey\":\"object key\"}"), engine.map(source, json("""
            {"object":{"number":{"from":"/a~1b/~0name/0/"},"empty":{"from":"/"},"numericKey":{"from":"/01"}}}
            """)));
        JsonNode copiedRoot = engine.map(source, from(""));
        assertEquals(source, copiedRoot);
        assertNotSame(source, copiedRoot);
    }

    @Test
    void optionalMissingMembersAreOmittedWhileExplicitNullAndFalsyValuesRemain() throws Exception {
        JsonNode result = engine.map(json("{\"nothing\":null,\"zero\":0,\"false\":false,\"empty\":\"\"}"), json("""
            {"object":{"omitted":{"from":"/missing","required":false},"nothing":{"from":"/nothing"},
              "zero":{"from":"/zero","required":false},"false":{"from":"/false"},"empty":{"from":"/empty"}}}
            """));

        assertFalse(result.has("omitted"));
        assertEquals(json("{\"nothing\":null,\"zero\":0,\"false\":false,\"empty\":\"\"}"), result);
        assertEquals(NullNode.getInstance(), engine.map(NullNode.getInstance(), from("")));
        assertEquals(NullNode.getInstance(), engine.map(json("{}"), json("{\"literal\":null}")));
    }

    @Test
    void missingRequiredSelectionsAndMissingOptionalRootAreRejected() throws Exception {
        reject(json("{}"), json("{\"from\":\"/missing\"}"));
        reject(json("{}"), json("{\"from\":\"/missing\",\"required\":true}"));
        reject(json("{}"), json("{\"from\":\"/missing\",\"required\":false}"));
        reject(json("[1]"), json("{\"from\":\"/01\"}"));
        reject(json("[1]"), json("{\"from\":\"/-\"}"));
    }

    @Test
    void preservesDecimalScalePrecisionAndLiteralDataWithoutInterpretingIt() throws Exception {
        BigDecimal precise = new BigDecimal("123456789.123456789000");
        ObjectNode source = mapper.createObjectNode().set("value", DecimalNode.valueOf(precise));
        ObjectNode expression = mapper.createObjectNode().set("literal", DecimalNode.valueOf(precise));

        assertEquals(precise, engine.map(source, from("/value")).decimalValue());
        assertEquals(precise, engine.map(source, expression).decimalValue());
        JsonNode literal = json("{\"from\":\"/not-an-operation\",\"required\":\"not-a-boolean\",\"object\":[1,2]}");
        assertEquals(literal, engine.map(source, mapper.createObjectNode().set("literal", literal)));
        assertDoesNotThrow(() -> engine.validate(literal(literal)));
    }

    @Test
    void numericScaleBoundariesRemainExactForSourceAndLiteralValues() {
        for (int scale : new int[] {-1000, 1000}) {
            BigDecimal number = new BigDecimal(BigInteger.ONE, scale);
            JsonNode value = DecimalNode.valueOf(number);
            assertEquals(number, engine.map(value, from("")).decimalValue());
            assertEquals(number, engine.map(NullNode.getInstance(), literal(value)).decimalValue());
            assertDoesNotThrow(() -> engine.validate(literal(value)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1001, 1001, Integer.MIN_VALUE})
    void excessiveAbsoluteScaleIsRejectedEvenWhenTheSourceIsNotSelected(int scale) {
        JsonNode value = DecimalNode.valueOf(new BigDecimal(BigInteger.ONE, scale));
        reject(value, literal(NullNode.getInstance()));
        reject(NullNode.getInstance(), literal(value));
        rejectDefinition(literal(value));
    }

    @Test
    void nonfiniteSourceAndLiteralNumbersAreRejectedWithoutCoercion() {
        for (double number : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JsonNode value = mapper.getNodeFactory().numberNode(number);
            reject(value, literal(NullNode.getInstance()));
            reject(NullNode.getInstance(), literal(value));
            rejectDefinition(literal(value));
        }
    }

    @Test
    void selectedAndLiteralContainersAreIndependentOfTheirInputsAndOfEachOther() throws Exception {
        ObjectNode source = (ObjectNode) json("{\"data\":{\"items\":[1]}}");
        ObjectNode mapping = (ObjectNode) json("""
            {"object":{"first":{"from":"/data"},"second":{"from":"/data"},"literal":{"literal":{"items":[2]}}}}
            """);
        JsonNode sourceBefore = source.deepCopy();
        JsonNode mappingBefore = mapping.deepCopy();
        JsonNode result = engine.map(source, mapping);

        ((ArrayNode) result.path("first").path("items")).add(3);
        ((ArrayNode) result.path("literal").path("items")).add(4);
        assertEquals(json("[1]"), result.path("second").path("items"));
        assertEquals(sourceBefore, source);
        assertEquals(mappingBefore, mapping);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "null", "[]", "1", "{\"from\":1}", "{\"from\":null}", "{\"object\":[]}",
        "{\"object\":null}", "{\"from\":\"\",\"literal\":null}", "{\"literal\":1,\"object\":{}}",
        "{\"literal\":1,\"required\":false}", "{\"from\":\"\",\"required\":\"false\"}",
        "{\"from\":\"\",\"required\":null}", "{\"from\":\"\",\"extra\":1}", "{\"unknown\":\"private-marker\"}"
    })
    void rejectsInvalidOperationsAndExtraFieldsWithSafeErrors(String mapping) throws Exception {
        reject(json("{\"private-marker\":\"private-marker\"}"), json(mapping));
        rejectDefinition(json(mapping));
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "#/field", "/bad~", "/bad~2", "/bad~x", "/~01~2"})
    void rejectsNonPointerAndInvalidEscapeSyntax(String pointer) throws Exception {
        reject(json("{}"), from(pointer));
        rejectDefinition(from(pointer));
    }

    @Test
    void pointerLengthAndSegmentLimitsIncludeTheirBoundaries() throws Exception {
        String key = "a".repeat(511);
        assertEquals(json("1"), engine.map(mapper.createObjectNode().put(key, 1), from("/" + key)));
        assertDoesNotThrow(() -> engine.validate(from("/" + key)));
        reject(mapper.createObjectNode().put(key + "a", 1), from("/" + key + "a"));
        rejectDefinition(from("/" + key + "a"));

        ObjectNode optional = from("/x".repeat(32)).put("required", false);
        ObjectNode mapping = mapper.createObjectNode().set("object", mapper.createObjectNode().set("value", optional));
        assertEquals(json("{}"), engine.map(json("{}"), mapping));
        assertDoesNotThrow(() -> engine.validate(mapping));
        optional.put("from", "/x".repeat(33));
        reject(json("{}"), mapping);
        rejectDefinition(mapping);
    }

    @Test
    void mappingFieldNamesHaveA128CharacterLimit() throws Exception {
        ObjectNode fields = mapper.createObjectNode().set("a".repeat(128), literal(NullNode.getInstance()));
        assertTrue(engine.map(json("{}"), mapper.createObjectNode().set("object", fields)).has("a".repeat(128)));
        assertDoesNotThrow(() -> engine.validate(mapper.createObjectNode().set("object", fields)));
        fields.set("b".repeat(129), literal(NullNode.getInstance()));
        reject(json("{}"), mapper.createObjectNode().set("object", fields));
        rejectDefinition(mapper.createObjectNode().set("object", fields));
    }

    @Test
    void sourceAndLiteralTreesHave32LevelAnd5000NodeLimits() throws Exception {
        JsonNode depth32 = nestedArray(32);
        assertEquals(depth32, engine.map(depth32, from("")));
        assertEquals(depth32, engine.map(json("{}"), literal(depth32)));
        assertDoesNotThrow(() -> engine.validate(literal(depth32)));
        reject(nestedArray(33), literal(NullNode.getInstance()));
        reject(json("{}"), literal(nestedArray(33)));
        rejectDefinition(literal(nestedArray(33)));

        ArrayNode nodes5000 = array(4999);
        assertEquals(nodes5000, engine.map(nodes5000, from("")));
        assertEquals(nodes5000, engine.map(json("{}"), literal(nodes5000)));
        assertDoesNotThrow(() -> engine.validate(literal(nodes5000)));
        reject(array(5000), literal(NullNode.getInstance()));
        reject(json("{}"), literal(array(5000)));
        rejectDefinition(literal(array(5000)));
    }

    @Test
    void expressionNestingAndNodeBudgetsCountOperationsRatherThanLiteralData() throws Exception {
        assertEquals(NullNode.getInstance(), engine.map(json("{}"), literal(NullNode.getInstance())));
        assertEquals(nestedObjectValue(16), engine.map(json("{}"), nestedObjectExpression(16)));
        assertDoesNotThrow(() -> engine.validate(nestedObjectExpression(16)));
        reject(json("{}"), nestedObjectExpression(17));
        rejectDefinition(nestedObjectExpression(17));
        ObjectNode fields = mapper.createObjectNode();
        for (int i = 0; i < 255; i++) fields.set("field" + i, literal(NullNode.getInstance()));
        assertEquals(255, engine.map(json("{}"), mapper.createObjectNode().set("object", fields)).size());
        assertDoesNotThrow(() -> engine.validate(mapper.createObjectNode().set("object", fields)));
        fields.set("overflow", literal(NullNode.getInstance()));
        reject(json("{}"), mapper.createObjectNode().set("object", fields));
        rejectDefinition(mapper.createObjectNode().set("object", fields));
    }

    @Test
    void repeatedSelectionsAreRejectedBeforeTheirContainersAreCopied() throws Exception {
        CountingArrayNode selected = new CountingArrayNode();
        for (int i = 0; i < 2499; i++) selected.addNull();
        ObjectNode source = mapper.createObjectNode().set("data", selected);

        reject(source, json("{\"object\":{\"first\":{\"from\":\"/data\"},\"second\":{\"from\":\"/data\"}}}"));

        assertTrue(selected.copies <= 1, "Output expansion must be checked before copying the repeated selection.");
    }

    @Test
    void outputBytesCountUtf8AndJsonEscapingAtTheExactLimit() throws Exception {
        for (String unit : new String[] {"é", "\n"}) {
            JsonNode exact = mapper.getNodeFactory().textNode(unit.repeat(65535));
            assertEquals(131072, mapper.writeValueAsBytes(exact).length);
            assertEquals(exact, engine.map(NullNode.getInstance(), literal(exact)));
            reject(NullNode.getInstance(), literal(mapper.getNodeFactory().textNode(unit.repeat(65536))));
        }
    }

    @Test
    void outputBudgetIncludesObjectKeysAndPunctuationDuringAmplification() throws Exception {
        String key = "\n".repeat(128);
        ObjectNode exact = mapper.createObjectNode().put(key, "a".repeat(130809));
        assertEquals(131072, mapper.writeValueAsBytes(exact).length);
        ObjectNode fields = mapper.createObjectNode().set(key, literal(exact.get(key)));
        assertEquals(exact, engine.map(NullNode.getInstance(), mapper.createObjectNode().set("object", fields)));
        fields.set("extra", literal(NullNode.getInstance()));
        reject(NullNode.getInstance(), mapper.createObjectNode().set("object", fields));

        ObjectNode source = mapper.createObjectNode().put("text", "a".repeat(70000));
        reject(source, json("{\"object\":{\"a\":{\"from\":\"/text\"},\"b\":{\"from\":\"/text\"}}}"));
    }

    @Test
    void validatesRequiredPointersWithoutResolvingThemOrRequiringASource() throws Exception {
        assertDoesNotThrow(() -> engine.validate(from("")));
        assertDoesNotThrow(() -> engine.validate(from("/missing/required")));
        assertDoesNotThrow(() -> engine.validate(from("/01")));
        assertDoesNotThrow(() -> engine.validate(json("""
            {"object":{"required":{"from":"/a~1b/~0name/0/","required":true},
              "optional":{"from":"/missing","required":false},"nothing":{"literal":null}}}
            """)));
    }

    @Test
    void definitionValidationTraversesBranchesAfterRequiredSourceReferences() throws Exception {
        rejectDefinition(json("""
            {"object":{"first":{"from":"/required-but-not-resolved"},
              "later":{"object":{"nested":{"from":"/private-marker~2"}}}}}
            """));
        rejectDefinition(json("""
            {"object":{"first":{"from":"/required-but-not-resolved"},
              "later":{"object":{"nested":{"literal":1,"extra":"private-marker"}}}}}
            """));
    }

    @Test
    void definitionValidationLeavesNestedMappingsAndLiteralDataUnchanged() throws Exception {
        JsonNode mapping = json("""
            {"object":{"source":{"from":"/future/required"},
              "data":{"literal":{"from":"not-an-expression","values":[null,true,0,"2026-10-23"]}}}}
            """ );
        JsonNode before = mapping.deepCopy();

        engine.validate(mapping);

        assertEquals(before, mapping);
    }

    private void rejectDefinition(JsonNode mapping) {
        BusinessException failure = assertThrows(BusinessException.class, () -> engine.validate(mapping));
        assertEquals(ErrorCode.BAD_REQUEST, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("mapping"));
        assertFalse(failure.getMessage().contains("private-marker"));
    }

    private void reject(JsonNode source, JsonNode mapping) {
        BusinessException failure = assertThrows(BusinessException.class, () -> engine.map(source, mapping));
        assertEquals(ErrorCode.BAD_REQUEST, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("mapping"));
        assertFalse(failure.getMessage().contains("private-marker"));
    }

    private ObjectNode from(String pointer) {
        return mapper.createObjectNode().put("from", pointer);
    }

    private ObjectNode literal(JsonNode value) {
        return mapper.createObjectNode().set("literal", value);
    }

    private ArrayNode array(int items) {
        ArrayNode result = mapper.createArrayNode();
        for (int i = 0; i < items; i++) result.addNull();
        return result;
    }

    private JsonNode nestedArray(int depth) {
        JsonNode result = NullNode.getInstance();
        for (int i = 1; i < depth; i++) result = mapper.createArrayNode().add(result);
        return result;
    }

    private JsonNode nestedObjectExpression(int depth) {
        JsonNode result = literal(NullNode.getInstance());
        for (int i = 1; i < depth; i++) {
            result = mapper.createObjectNode().set("object", mapper.createObjectNode().set("child", result));
        }
        return result;
    }

    private JsonNode nestedObjectValue(int depth) {
        JsonNode result = NullNode.getInstance();
        for (int i = 1; i < depth; i++) result = mapper.createObjectNode().set("child", result);
        return result;
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    /** Detects unbounded copies of one selected subtree when several fields expand the same source. */
    private static final class CountingArrayNode extends ArrayNode {
        private int copies;

        private CountingArrayNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public ArrayNode deepCopy() {
            copies++;
            return super.deepCopy();
        }
    }
}
