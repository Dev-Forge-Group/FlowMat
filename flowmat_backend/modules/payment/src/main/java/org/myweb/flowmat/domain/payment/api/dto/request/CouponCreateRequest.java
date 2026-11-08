package org.myweb.flowmat.domain.payment.api.dto.request;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CouponCreateRequest(
        String couponCode,
        String couponName,
        String discountType,        // rate / fixed
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Integer usageLimit,
        Integer perUserLimit,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil
) {}
