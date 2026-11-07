package org.myweb.flowmat.domain.payment.api.dto.request;

public record PlanCreateRequest(
        String planCode,
        String planName,
        String planDesc,
        Integer maxUsers,
        Integer maxFactories,
        Integer displayOrder
) {}
