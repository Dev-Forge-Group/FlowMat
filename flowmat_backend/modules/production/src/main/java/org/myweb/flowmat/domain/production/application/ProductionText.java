package org.myweb.flowmat.domain.production.application;

import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Validates raw text before normalization can hide characters PostgreSQL cannot store. */
final class ProductionText {

    private ProductionText() {}

    static String trimToNull(String value, String field) {
        requireStorable(value, field);
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    static void requireStorable(String value, String field) {
        if (value == null) return;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isLowSurrogate(character)) throw invalid(field);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid(field);
                }
                index++;
            }
        }
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(ErrorCode.BAD_REQUEST,
            field + " contains a character PostgreSQL cannot store.");
    }
}
