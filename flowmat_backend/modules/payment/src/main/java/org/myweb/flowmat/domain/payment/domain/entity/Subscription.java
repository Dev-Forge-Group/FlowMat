package org.myweb.flowmat.domain.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
@Table(name = "subscription")
public class Subscription extends BaseTimeEntity {

    @Id
    private UUID id;

    // users.user_id 참조 (varchar(50)) - 프로젝트 내 타 테이블과 참조 방식 통일
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_pricing_id", nullable = false)
    private PlanPricing planPricing;

    // subscription (자동갱신) / one_time (기간 일회성)
    @Column(name = "billing_type", nullable = false, length = 20)
    private String billingType = "subscription";

    // active / expired / cancelled / paused / trial
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "expired_at", nullable = false)
    private OffsetDateTime expiredAt;

    // 다음 결제 예정일 (one_time 이면 NULL)
    @Column(name = "next_billing_at")
    private OffsetDateTime nextBillingAt;

    // Y / N
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auto_renew", nullable = false, length = 1)
    private String autoRenew = "Y";

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    // 무료체험 종료일 (체험 없으면 NULL)
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;
}
