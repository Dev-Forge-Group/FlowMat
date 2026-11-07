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
@Table(name = "promotion")
public class Promotion extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "promotion_name", nullable = false, length = 100)
    private String promotionName;

    @Column(name = "promotion_desc", columnDefinition = "text")
    private String promotionDesc;

    // rate (비율할인) / fixed (정액할인)
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    @Column(name = "discount_value", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountValue;

    // 적용 대상 요금제 ID (NULL = 전체 요금제, FK 없이 nullable)
    @Column(name = "target_plan_id", columnDefinition = "uuid")
    private UUID targetPlanId;

    // 적용 대상 개월수 (NULL = 개월수 무관)
    @Column(name = "target_billing_months")
    private Integer targetBillingMonths;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    // NULL = 상시 프로모션
    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    // active / inactive / ended
    @Column(name = "promotion_status", nullable = false, length = 20)
    private String promotionStatus = "active";
}
