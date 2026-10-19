package org.myweb.flowmat.global.util;

import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/**
 * Refuses text PostgreSQL cannot store, before trimming can hide it: a NUL or a broken UTF-16 pair. The same rule production
 * text follows (production {@code ProductionText}); the message names the field.
 */
public final class StorableText {

    private StorableText() {
    }

    /** 400 {@code <field> contains a character PostgreSQL cannot store.} when {@code value} holds such a character. */
    public static void require(String value, String field) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || Character.isLowSurrogate(character)) {
                throw invalid(field);
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid(field);
                }
                index++;
            }
        }
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(ErrorCode.BAD_REQUEST, field + " contains a character PostgreSQL cannot store.");
    }
}
