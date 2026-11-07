package org.myweb.flowmat.global.util;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public final class CsvDecimalParser {

    private static final Pattern GROUPED_NUMBER = Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    private CsvDecimalParser() {
    }

    public static BigDecimal parse(String value) {
        if (value.indexOf(',') >= 0 && !GROUPED_NUMBER.matcher(value).matches()) {
            throw new NumberFormatException("Invalid thousands separators: " + value);
        }
        return new BigDecimal(value.replace(",", ""));
    }

    public static boolean hasMoreThanIntegerDigits(BigDecimal value, int maximum) {
        return value.signum() != 0 && (long) value.precision() - value.scale() > maximum;
    }
}
