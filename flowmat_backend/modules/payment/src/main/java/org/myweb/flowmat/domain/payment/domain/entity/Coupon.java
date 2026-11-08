package org.myweb.flowmat.domain.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "coupon")
public class Coupon extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "coupon_code", nullable = false, unique = true, length = 50)
    private String couponCode;

    @Column(name = "coupon_name", nullable = false, length = 100)
    private String couponName;

    // rate (비율할인) / fixed (정액할인)
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    // rate: 10.00 = 10% / fixed: 5000.00 = 5,000원
    @Column(name = "discount_value", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountValue;

    // 최대 할인 한도 (NULL = 한도 없음, rate 타입에서만 의미있음)
    @Column(name = "max_discount_amount", precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;

    // 최소 결제 금액 조건 (NULL = 조건 없음)
    @Column(name = "min_order_amount", precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    // 총 사용 가능 횟수 (NULL = 무제한)
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    // 1인당 사용 가능 횟수
    @Column(name = "per_user_limit")
    private Integer perUserLimit = 1;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    // NULL = 기한 없음
    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    // active / inactive / expired
    @Column(name = "coupon_status", nullable = false, length = 20)
    private String couponStatus = "active";
}
