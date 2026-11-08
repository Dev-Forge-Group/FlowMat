package org.myweb.flowmat.domain.payment.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Coupon;

public record CouponResponse(
        UUID id,
        String couponCode,
        String couponName,
        String discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Integer usageLimit,
        Integer usedCount,
        Integer perUserLimit,
        OffsetDateTime validFrom,
        OffsetDateTime validUntil,
        String couponStatus
) {
    public static CouponResponse from(Coupon c) {
        return new CouponResponse(
                c.getId(),
                c.getCouponCode(),
                c.getCouponName(),
                c.getDiscountType(),
                c.getDiscountValue(),
                c.getMaxDiscountAmount(),
                c.getMinOrderAmount(),
                c.getUsageLimit(),
                c.getUsedCount(),
                c.getPerUserLimit(),
                c.getValidFrom(),
                c.getValidUntil(),
                c.getCouponStatus()
        );
    }
}
