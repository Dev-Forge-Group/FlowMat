package org.myweb.flowmat.domain.quality.application;

import java.math.BigDecimal;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Quality decisions must retain their numeric evidence in numeric(14,4) columns. */
final class QualityNumbers {

    private QualityNumbers() {
    }

    static BigDecimal requireStorable(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // Check the magnitude before stripping zeros; do not expand untrusted exponents.
        if ((long) value.precision() - value.scale() > 10 || value.stripTrailingZeros().scale() > 4) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                field + " must fit 10 integer digits and 4 decimal places without rounding.");
        }
        return value;
    }
}
