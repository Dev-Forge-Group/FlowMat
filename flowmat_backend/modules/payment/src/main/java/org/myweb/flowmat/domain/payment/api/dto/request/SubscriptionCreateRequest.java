package org.myweb.flowmat.domain.payment.api.dto.request;

import java.util.UUID;

public record SubscriptionCreateRequest(
        UUID planId,
        UUID planPricingId,
        String billingType   // subscription / one_time
) {}
