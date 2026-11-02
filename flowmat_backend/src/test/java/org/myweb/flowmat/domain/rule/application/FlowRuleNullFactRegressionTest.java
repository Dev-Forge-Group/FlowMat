package org.myweb.flowmat.domain.rule.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A fact whose final property exists with a null value compares equal to the null literal.
 */
class FlowRuleNullFactRegressionTest {
    private final FlowRuleExpressionEvaluator evaluator = new FlowRuleExpressionEvaluator(new ObjectMapper());

    @ParameterizedTest(name = "{0}: {2} should be {3}")
    @MethodSource("presentNullComparisons")
    void comparesAPresentNullFactAsNull(String shape, Map<String, Object> facts, String expression, boolean expected) {
        assertEquals(expected, evaluator.evaluate("expression", expression, facts), shape + ": " + expression);
    }

    private static Stream<Arguments> presentNullComparisons() {
        Map<String, Object> topLevel = new HashMap<>();
        topLevel.put("value", null);

        Map<String, Object> nested = new HashMap<>();
        nested.put("value", null);
        Map<String, Object> nestedFacts = new HashMap<>();
        nestedFacts.put("item", nested);

        Map<String, Object> recordFacts = new HashMap<>();
        recordFacts.put("item", new NullableFact(null));

        return Stream.of(
            Arguments.of("top-level key", topLevel, "value == null", true),
            Arguments.of("top-level key", topLevel, "value != null", false),
            Arguments.of("nested map key", nestedFacts, "item.value == null", true),
            Arguments.of("nested map key", nestedFacts, "item.value != null", false),
            Arguments.of("record property", recordFacts, "item.value == null", true),
            Arguments.of("record property", recordFacts, "item.value != null", false)
        );
    }

    private record NullableFact(String value) {
    }
}
