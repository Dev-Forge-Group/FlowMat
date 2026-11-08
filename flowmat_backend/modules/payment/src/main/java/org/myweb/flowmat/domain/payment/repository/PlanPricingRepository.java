package org.myweb.flowmat.domain.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Plan;
import org.myweb.flowmat.domain.payment.domain.entity.PlanPricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanPricingRepository extends JpaRepository<PlanPricing, UUID> {

    List<PlanPricing> findAllByPlanAndIsActiveOrderByBillingMonthsAsc(Plan plan, String isActive);

    Optional<PlanPricing> findByPlanAndBillingMonthsAndIsActive(Plan plan, Integer billingMonths, String isActive);
}
