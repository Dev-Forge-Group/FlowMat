package org.myweb.flowmat.domain.payment.api.dto.request;

import java.util.UUID;

public record PaymentCreateRequest(
        UUID subscriptionId,
        UUID planPricingId,
        String paymentMethod,   // card / bank_transfer / virtual_account
        String pgProvider,
        UUID couponId,          // 쿠폰 미사용 시 null
        UUID promotionId        // 프로모션 미적용 시 null
) {}
