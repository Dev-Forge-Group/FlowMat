package org.myweb.flowmat.domain.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.exception.BusinessException;

class QualityNumbersTest {

    @Test
    void optionalValuesStayOptional() {
        assertThat(QualityNumbers.requireStorable(null, "measuredValue")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0e2147483647", "0e-2147483647", "1.2345", "1.234500", "12345e-4",
        "9999999999.9999", "-9999999999.9999", "1e9", "1e-4", "-0.0001", "1234567890.0000"})
    void exactStorageIncludesTrailingZerosExponentsAndNegativeMeasurements(String input) {
        BigDecimal value = new BigDecimal(input);
        assertThat(QualityNumbers.requireStorable(value, "measuredValue")).isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00001", "-1e-5", "1.23456", "10000000000", "-10000000000",
        "9999999999.99999", "1e2147483647", "1e-2147483647"})
    void valuesThatWouldRoundOrOverflowAreRejectedWithoutExpandingTheirExponent(String input) {
        assertThatThrownBy(() -> QualityNumbers.requireStorable(new BigDecimal(input), "measuredValue"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("measuredValue must fit 10 integer digits and 4 decimal places without rounding.");
    }
}
