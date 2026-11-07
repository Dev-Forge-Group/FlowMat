package org.myweb.flowmat.domain.payment.api.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

public record PlanPricingCreateRequest(
        UUID planId,
        Integer billingMonths,
        BigDecimal unitPrice,
        BigDecimal discountRate
) {}
