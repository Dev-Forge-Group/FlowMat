package org.myweb.flowmat.domain.payment.api.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Subscription;

public record SubscriptionResponse(
        UUID id,
        String userId,
        UUID planId,
        String planName,
        Integer billingMonths,
        String billingType,
        String status,
        String autoRenew,
        OffsetDateTime startedAt,
        OffsetDateTime expiredAt,
        OffsetDateTime nextBillingAt
) {
    public static SubscriptionResponse from(Subscription s) {
        return new SubscriptionResponse(
                s.getId(),
                s.getUserId(),
                s.getPlan().getId(),
                s.getPlan().getPlanName(),
                s.getPlanPricing().getBillingMonths(),
                s.getBillingType(),
                s.getStatus(),
                s.getAutoRenew(),
                s.getStartedAt(),
                s.getExpiredAt(),
                s.getNextBillingAt()
        );
    }
}
