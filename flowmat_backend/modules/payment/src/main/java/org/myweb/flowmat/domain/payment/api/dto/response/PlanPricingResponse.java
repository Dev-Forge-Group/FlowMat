package org.myweb.flowmat.domain.payment.api.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.PlanPricing;

public record PlanPricingResponse(
        UUID id,
        UUID planId,
        Integer billingMonths,
        BigDecimal unitPrice,
        BigDecimal discountRate,
        BigDecimal totalPrice,
        String currency
) {
    public static PlanPricingResponse from(PlanPricing pp) {
        return new PlanPricingResponse(
                pp.getId(),
                pp.getPlan().getId(),
                pp.getBillingMonths(),
                pp.getUnitPrice(),
                pp.getDiscountRate(),
                pp.getTotalPrice(),
                pp.getCurrency()
        );
    }
}
