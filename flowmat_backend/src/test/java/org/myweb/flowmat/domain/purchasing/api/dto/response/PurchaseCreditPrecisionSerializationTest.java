package org.myweb.flowmat.domain.purchasing.api.dto.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Exact credit text is additive; existing numeric fields keep their JSON type and value. */
class PurchaseCreditPrecisionSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @ParameterizedTest(name = "credit amount {0} retains exact text and its legacy numeric field")
    @MethodSource("creditAmounts")
    void currentAndHistoricalCreditAmountsSerializeExactly(String input, String expectedText) throws Exception {
        BigDecimal amount = input == null ? null : new BigDecimal(input);
        PurchaseOrderResponse.CreditChange change = new PurchaseOrderResponse.CreditChange(amount, null, "Supplier correction", "owner", null);
        PurchaseOrderResponse.Return returned = new PurchaseOrderResponse.Return("return-1", "line-1", "ITEM-1", BigDecimal.ONE,
            "stock-1", "WH-A", null, "Wet bags", "owner", null, null, null, false, amount, null, null, null, List.of(change));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(returned));

        assertAmount(json, "creditAmount", "creditAmountText", amount, expectedText);
        assertAmount(json.path("creditChanges").get(0), "amount", "amountText", amount, expectedText);
    }

    private static void assertAmount(JsonNode json, String numericField, String textField, BigDecimal amount, String expectedText) {
        assertTrue(json.has(numericField), json.toString());
        assertTrue(json.has(textField), json.toString());
        if (amount == null) {
            assertTrue(json.path(numericField).isNull(), json.toString());
            assertTrue(json.path(textField).isNull(), json.toString());
        } else {
            assertTrue(json.path(numericField).isNumber(), json.toString());
            assertEquals(0, amount.compareTo(json.path(numericField).decimalValue()), json.toString());
            assertTrue(json.path(textField).isTextual(), json.toString());
            assertEquals(expectedText, json.path(textField).textValue());
        }
    }

    private static Stream<Arguments> creditAmounts() {
        return Stream.of(
            Arguments.of("9007199254740993", "9007199254740993"),
            Arguments.of("99999999999999999999.9999", "99999999999999999999.9999"),
            Arguments.of("0.0000", "0.0000"),
            Arguments.of("1E+6", "1000000"),
            Arguments.of(null, null)
        );
    }
}
