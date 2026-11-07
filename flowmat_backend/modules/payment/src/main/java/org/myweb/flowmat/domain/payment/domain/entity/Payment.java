package org.myweb.flowmat.domain.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "payment")
public class Payment extends BaseTimeEntity {

    @Id
    private UUID id;

    // users.user_id 참조 (varchar(50)) - 프로젝트 내 타 테이블과 참조 방식 통일
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    // 결제 당시 단가 이력 보존용 (나중에 가격 바껴도 당시 금액 기록 유지)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_pricing_id", nullable = false)
    private PlanPricing planPricing;

    // FK 없이 nullable - coupon 테이블 없어도 동작
    @Column(name = "coupon_id", columnDefinition = "uuid")
    private UUID couponId;

    // FK 없이 nullable - promotion 테이블 없어도 동작
    @Column(name = "promotion_id", columnDefinition = "uuid")
    private UUID promotionId;

    // 할인 전 원래 금액
    @Column(name = "original_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal originalAmount;

    // 총 할인 금액
    @Column(name = "discount_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    // 실제 결제 금액 = original - discount
    @Column(name = "final_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal finalAmount;

    // ISO 4217
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KRW";

    // card / bank_transfer / virtual_account
    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    // pending / completed / failed / refunded / cancelled
    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = "pending";

    // PG사 이름 (토스페이먼츠 / 카카오페이 등)
    @Column(name = "pg_provider", length = 30)
    private String pgProvider;

    // PG사 발급 거래 고유번호
    @Column(name = "pg_transaction_id", length = 200, unique = true)
    private String pgTransactionId;

    // PG사 승인 완료 일시
    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    // PG사 응답 실패 사유
    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;
}
