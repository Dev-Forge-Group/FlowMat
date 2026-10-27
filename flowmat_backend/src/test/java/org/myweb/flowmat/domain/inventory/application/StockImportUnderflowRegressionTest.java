package org.myweb.flowmat.domain.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StockImportUnderflowRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"1e-2147483647", "-1e-2147483647", "0e-2147483647", "0.000049999", "-0.000049999"})
    void aValueThatRoundsToZeroRemainsARowError(String token) {
        // The maximum scale fails immediately in the JDK; intermediate huge exponents must not be substituted here.
        List<String> problems = new ArrayList<>();
        assertNull(StockImportService.storedQuantity(new BigDecimal(token), "Quantity", problems));
        assertEquals(List.of("Quantity is too small to store at four decimal places"), problems);
    }

    @ParameterizedTest
    @CsvSource({"0.00005,0.0001", "-0.00005,-0.0001", "0.00015,0.0002", "5,5.0000",
        "9999999999.99994,9999999999.9999"})
    void keepsHalfUpTiesAndTheLargestStorableRoundedQuantity(String token, String expected) {
        List<String> problems = new ArrayList<>();
        assertEquals(new BigDecimal(expected), StockImportService.storedQuantity(new BigDecimal(token), "Quantity", problems));
        assertEquals(List.of(), problems);
    }
}
