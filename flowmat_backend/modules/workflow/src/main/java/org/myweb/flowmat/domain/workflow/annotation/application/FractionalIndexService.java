package org.myweb.flowmat.domain.workflow.annotation.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class FractionalIndexService {

    public String between(String left, String right) {
        if (left == null && right == null) {
            return "1024";
        }
        if (left == null) {
            return decimal(right).subtract(BigDecimal.ONE).stripTrailingZeros().toPlainString();
        }
        if (right == null) {
            return decimal(left).add(BigDecimal.ONE).stripTrailingZeros().toPlainString();
        }
        BigDecimal result = decimal(left).add(decimal(right))
            .divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP)
            .stripTrailingZeros();
        return result.toPlainString();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value.trim());
    }
}
