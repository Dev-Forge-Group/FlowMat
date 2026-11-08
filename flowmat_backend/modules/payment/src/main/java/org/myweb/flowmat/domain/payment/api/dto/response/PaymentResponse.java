package org.myweb.flowmat.domain.payment.api.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Payment;

public record PaymentResponse(
        UUID id,
        String userId,
        UUID subscriptionId,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal finalAmount,
        String currency,
        String paymentMethod,
        String paymentStatus,
        String pgProvider,
        String pgTransactionId,
        OffsetDateTime paidAt,
        BigDecimal refundAmount,
        OffsetDateTime refundedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getUserId(),
                p.getSubscription().getId(),
                p.getOriginalAmount(),
                p.getDiscountAmount(),
                p.getFinalAmount(),
                p.getCurrency(),
                p.getPaymentMethod(),
                p.getPaymentStatus(),
                p.getPgProvider(),
                p.getPgTransactionId(),
                p.getPaidAt(),
                p.getRefundAmount(),
                p.getRefundedAt()
        );
    }
}
