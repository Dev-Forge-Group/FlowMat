package org.myweb.flowmat.domain.workflow.domain.contract;

import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** PostgreSQL text and jsonb cannot represent NUL or an isolated UTF-16 surrogate. */
public final class WorkflowText {

    private WorkflowText() {}

    public static void requireStorable(String value, String field) {
        if (value == null) return;
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
        return new BusinessException(ErrorCode.BAD_REQUEST,
            field + " contains a character PostgreSQL cannot store.");
    }
}
