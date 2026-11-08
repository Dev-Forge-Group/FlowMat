package org.myweb.flowmat.domain.payment.application;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanPricingResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanResponse;
import org.myweb.flowmat.domain.payment.domain.entity.Plan;
import org.myweb.flowmat.domain.payment.repository.PlanPricingRepository;
import org.myweb.flowmat.domain.payment.repository.PlanRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanServiceImpl implements PlanService {

    private final PlanRepository planRepository;
    private final PlanPricingRepository planPricingRepository;

    @Override
    public List<PlanResponse> getActivePlans() {
        return planRepository.findAllByPlanStatusOrderByDisplayOrderAsc("active")
                .stream()
                .map(PlanResponse::from)
                .toList();
    }

    @Override
    public PlanResponse getPlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return PlanResponse.from(plan);
    }

    @Override
    public List<PlanPricingResponse> getActivePricing(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return planPricingRepository.findAllByPlanAndIsActiveOrderByBillingMonthsAsc(plan, "Y")
                .stream()
                .map(PlanPricingResponse::from)
                .toList();
    }
}
