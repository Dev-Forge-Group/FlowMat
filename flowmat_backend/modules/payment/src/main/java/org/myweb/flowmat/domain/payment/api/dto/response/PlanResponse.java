package org.myweb.flowmat.domain.payment.api.dto.response;

import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Plan;

public record PlanResponse(
        UUID id,
        String planCode,
        String planName,
        String planDesc,
        Integer maxUsers,
        Integer maxFactories,
        String planStatus,
        Integer displayOrder
) {
    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.getId(),
                plan.getPlanCode(),
                plan.getPlanName(),
                plan.getPlanDesc(),
                plan.getMaxUsers(),
                plan.getMaxFactories(),
                plan.getPlanStatus(),
                plan.getDisplayOrder()
        );
    }
}
