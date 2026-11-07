package org.myweb.flowmat.domain.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "plan_pricing")
public class PlanPricing extends BaseTimeEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // 결제 개월수: 1 / 3 / 6 / 12
    @Column(name = "billing_months", nullable = false)
    private Integer billingMonths = 1;

    // 월 단가 (원)
    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    // 할인율 (%) ex) 0.00 / 10.00 / 20.00
    @Column(name = "discount_rate", precision = 5, scale = 2, nullable = false)
    private BigDecimal discountRate = BigDecimal.ZERO;

    // 최종 결제 금액 = unit_price × months × (1 - rate/100)
    @Column(name = "total_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalPrice;

    // ISO 4217 통화 코드
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KRW";

    // Y / N
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "is_active", nullable = false, length = 1)
    private String isActive = "Y";
}
