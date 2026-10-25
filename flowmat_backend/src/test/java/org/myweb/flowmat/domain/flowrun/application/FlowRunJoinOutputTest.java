package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FlowRunJoinOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noArrivalsHaveOnlyAnEmptyArrivalsArray() throws Exception {
        assertEquals(json("{\"arrivals\":[]}"), new FlowRunJoinOutput(mapper).result());
    }

    @Test
    void arrivalsKeepTheirOrderAndOnlyCopyTheAllowedNonNullFields() throws Exception {
        FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
        JsonNode first = json("""
            {"quantity":2,"unit":"kg","item":{"id":"ore"},"attrs":{"grade":"A"},
             "connectionId":"ignored","sourceStepId":"ignored","extra":"ignored"}
            """);
        JsonNode second = json("""
            {"quantity":3,"unit":"kg","item":{"id":"ore"},"attrs":{"grade":"B"}}
            """);

        joined.add("second-connection", "second-step", first);
        joined.add("first-connection", "first-step", second);

        JsonNode result = joined.result();
        assertEquals(2, result.path("arrivals").size());
        assertEquals(json("""
            {"connectionId":"second-connection","sourceStepId":"second-step","quantity":2,
             "unit":"kg","item":{"id":"ore"},"attrs":{"grade":"A"}}
            """), result.path("arrivals").get(0));
        assertEquals(json("""
            {"connectionId":"first-connection","sourceStepId":"first-step","quantity":3,
             "unit":"kg","item":{"id":"ore"},"attrs":{"grade":"B"}}
            """), result.path("arrivals").get(1));
        assertEquals(new BigDecimal("5"), result.path("quantity").decimalValue());
        assertEquals("kg", result.path("unit").asText());
        assertSame(first.get("item"), result.get("item"));
        assertSame(first.get("attrs"), result.path("arrivals").get(0).get("attrs"));
        assertFalse(result.has("attrs"));
        assertFalse(result.has("extra"));
    }

    @Test
    void largeAndTinyDecimalQuantitiesKeepTheirExactTotal() throws Exception {
        FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
        joined.add("large", "large-step", json("""
            {"quantity":9007199254740993.125,"unit":"kg"}
            """));
        joined.add("tiny", "tiny-step", json("""
            {"quantity":0.000000000000000000000000000001,"unit":"kg"}
            """));

        assertEquals(new BigDecimal("9007199254740993.125000000000000000000000000001"),
            joined.result().path("quantity").decimalValue());
    }

    @Test
    void numericQuantitiesIncludeZeroAndNegativeValuesWithoutUnits() throws Exception {
        FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
        joined.add("zero", "zero-step", json("{\"quantity\":0}"));
        joined.add("negative", "negative-step", json("{\"quantity\":-2.5}"));
        joined.add("positive", "positive-step", json("{\"quantity\":4}"));

        assertEquals(new BigDecimal("1.5"), joined.result().path("quantity").decimalValue());
        assertFalse(joined.result().has("unit"));
    }

    @Test
    void aMissingNullOrNonNumericQuantityPreventsOnlyTheTopLevelTotal() throws Exception {
        for (String output : new String[] {
            "{}", "{\"quantity\":null}", "{\"quantity\":\"2\"}",
            "{\"quantity\":true}", "{\"quantity\":{}}", "{\"quantity\":[]}",
        }) {
            FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
            joined.add("valid", "valid-step", json("{\"quantity\":1,\"unit\":\"kg\",\"item\":\"ore\"}"));
            joined.add("invalid", "invalid-step", json(output));

            JsonNode result = joined.result();
            assertFalse(result.has("quantity"), output);
            assertFalse(result.has("unit"), output);
            assertEquals(2, result.path("arrivals").size(), output);
            JsonNode arrival = result.path("arrivals").get(1);
            JsonNode supplied = json(output).get("quantity");
            if (supplied == null || supplied.isNull()) {
                assertFalse(arrival.has("quantity"), output);
            } else {
                assertEquals(supplied, arrival.get("quantity"), output);
            }
        }
    }

    @Test
    void missingAndNullUnitsAreEquivalentAndAreNotCopied() throws Exception {
        FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
        joined.add("missing", "missing-step", json("{\"quantity\":1}"));
        joined.add("null", "null-step", json("{\"quantity\":2,\"unit\":null}"));

        JsonNode result = joined.result();
        assertEquals(new BigDecimal("3"), result.path("quantity").decimalValue());
        assertFalse(result.has("unit"));
        assertFalse(result.path("arrivals").get(0).has("unit"));
        assertFalse(result.path("arrivals").get(1).has("unit"));
    }

    @Test
    void mismatchedUnitsPreventTheTotalButStillAllowACommonItem() throws Exception {
        for (String differentUnit : new String[] {"\"g\"", "null"}) {
            FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
            joined.add("first", "first-step", json("{\"quantity\":1,\"unit\":\"kg\",\"item\":\"ore\"}"));
            joined.add("second", "second-step", json("{\"quantity\":2,\"unit\":" + differentUnit + ",\"item\":\"ore\"}"));

            JsonNode result = joined.result();
            assertFalse(result.has("quantity"), differentUnit);
            assertFalse(result.has("unit"), differentUnit);
            assertEquals("ore", result.path("item").asText(), differentUnit);
        }
    }

    @Test
    void unitsCompareTheirTextValuesWhileArrivalsKeepTheOriginalNodes() throws Exception {
        for (String[] units : new String[][] {{"7", "\"7\"", "7"}, {"true", "\"true\"", "true"}, {"{}", "[]", ""}}) {
            FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
            JsonNode first = json("{\"quantity\":1,\"unit\":" + units[0] + "}");
            JsonNode second = json("{\"quantity\":2,\"unit\":" + units[1] + "}");
            joined.add("first", "first-step", first);
            joined.add("second", "second-step", second);

            JsonNode result = joined.result();
            assertEquals(new BigDecimal("3"), result.path("quantity").decimalValue());
            assertEquals(units[2], result.path("unit").asText());
            assertSame(first.get("unit"), result.path("arrivals").get(0).get("unit"));
            assertSame(second.get("unit"), result.path("arrivals").get(1).get("unit"));
        }
    }

    @Test
    void everyArrivalMustHaveTheSameNonNullItem() throws Exception {
        for (String itemField : new String[] {"", ",\"item\":null", ",\"item\":\"other\""}) {
            for (boolean absentFirst : new boolean[] {false, true}) {
                FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
                JsonNode complete = json("{\"quantity\":1,\"item\":\"ore\"}");
                JsonNode other = json("{\"quantity\":2" + itemField + "}");
                joined.add("first", "first-step", absentFirst ? other : complete);
                joined.add("second", "second-step", absentFirst ? complete : other);

                assertFalse(joined.result().has("item"), itemField);
                assertEquals(new BigDecimal("3"), joined.result().path("quantity").decimalValue());
            }
        }
    }

    @Test
    void itemsUseJsonNodeEqualityIncludingNumberTypesAndStructuredValues() throws Exception {
        FlowRunJoinOutput same = new FlowRunJoinOutput(mapper);
        JsonNode first = json("{\"item\":{\"id\":\"ore\",\"tags\":[\"a\",\"b\"]}}");
        same.add("first", "first-step", first);
        same.add("second", "second-step", json("{\"item\":{\"tags\":[\"a\",\"b\"],\"id\":\"ore\"}}"));
        assertSame(first.get("item"), same.result().get("item"));

        for (String[] items : new String[][] {{"1", "1.0"}, {"[1,2]", "[2,1]"}}) {
            FlowRunJoinOutput different = new FlowRunJoinOutput(mapper);
            different.add("first", "first-step", json("{\"item\":" + items[0] + "}"));
            different.add("second", "second-step", json("{\"item\":" + items[1] + "}"));
            assertFalse(different.result().has("item"));
        }
    }

    @Test
    void nullFieldsAndNonObjectOutputsStillRecordTheArrivalIdentity() throws Exception {
        for (String output : new String[] {"{\"quantity\":null,\"unit\":null,\"item\":null,\"attrs\":null}", "null", "[]", "7"}) {
            FlowRunJoinOutput joined = new FlowRunJoinOutput(mapper);
            joined.add("connection", "step", json(output));

            assertEquals(json("{\"arrivals\":[{\"connectionId\":\"connection\",\"sourceStepId\":\"step\"}]}"),
                joined.result(), output);
        }
    }

    private JsonNode json(String value) throws Exception {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
    }
}
