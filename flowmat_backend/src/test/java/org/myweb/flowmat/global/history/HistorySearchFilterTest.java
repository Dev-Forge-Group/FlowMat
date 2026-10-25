package org.myweb.flowmat.global.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class HistorySearchFilterTest {
    @Test
    void omittedFiltersDefaultToOneHundredRows() {
        var f = HistorySearchFilter.parse(null, null, null, null, null, null);
        assertEquals(100, f.limit());
        assertNull(f.action());
        assertNull(f.savedBy());
        assertNull(f.from());
        assertNull(f.to());
        assertNull(f.cursor());
        assertNotNull(f.<Object>specification(null, null));
    }

    @Test
    void validFiltersKeepTheirPreciseInstantsAndIdentifiers() {
        var f = HistorySearchFilter.parse("saved", "Actor-01", "2035-01-01T09:00:00.123456+09:00",
            "2035-01-01T00:00:00.123457Z", "1", "entry-01");
        assertEquals("saved", f.action());
        assertEquals("Actor-01", f.savedBy());
        assertEquals(OffsetDateTime.parse("2035-01-01T09:00:00.123456+09:00"), f.from());
        assertEquals(1, f.limit());
        assertEquals("entry-01", f.cursor());
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
            Arguments.of("action", "Saved"), Arguments.of("action", "delete"), Arguments.of("action", ""),
            Arguments.of("savedBy", ""), Arguments.of("savedBy", " \t"), Arguments.of("savedBy", "x".repeat(51)),
            Arguments.of("savedBy", "bad\u0000id"), Arguments.of("savedBy", "bad\ud800"),
            Arguments.of("cursor", ""), Arguments.of("cursor", " "), Arguments.of("cursor", "x".repeat(51)),
            Arguments.of("cursor", "bad\u0000id"), Arguments.of("cursor", "bad\udc00"),
            Arguments.of("from", "2035-01-01"), Arguments.of("from", "2035-01-01T00:00:00"),
            Arguments.of("from", ""), Arguments.of("from", "x".repeat(100)), Arguments.of("to", "tomorrow"),
            Arguments.of("from", "+999999999-01-01T00:00:00Z"), Arguments.of("to", "-999999999-01-01T00:00:00Z"),
            Arguments.of("to", ""), Arguments.of("limit", "0"), Arguments.of("limit", "101"),
            Arguments.of("limit", "1.5"), Arguments.of("limit", "-5"), Arguments.of("limit", ""),
            Arguments.of("limit", "999999999999999999999999999999999"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidFiltersNameTheFieldWithoutEchoingTheValue(String field, String value) {
        var failure = assertThrows(BusinessException.class, () -> HistorySearchFilter.parse(
            field.equals("action") ? value : null, field.equals("savedBy") ? value : null,
            field.equals("from") ? value : null, field.equals("to") ? value : null,
            field.equals("limit") ? value : null, field.equals("cursor") ? value : null));
        assertEquals(ErrorCode.BAD_REQUEST, failure.getErrorCode());
        assertTrue(failure.getMessage().contains(field));
        if (value.contains("\u0000") || value.length() > 50) assertTrue(!failure.getMessage().contains(value));
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    void upperBoundMustBeAfterTheLowerBound(String from, String to) {
        var failure = assertThrows(BusinessException.class,
            () -> HistorySearchFilter.parse(null, null, from, to, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("from"));
        assertTrue(failure.getMessage().contains("to"));
    }

    static Stream<Arguments> invalidRanges() {
        return Stream.of(Arguments.of("2035-01-02T00:00:00Z", "2035-01-01T00:00:00Z"),
            Arguments.of("2035-01-01T09:00:00+09:00", "2035-01-01T00:00:00Z"));
    }
}
