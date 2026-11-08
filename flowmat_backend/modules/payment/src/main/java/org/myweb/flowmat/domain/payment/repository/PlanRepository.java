package org.myweb.flowmat.domain.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByPlanCode(String planCode);

    List<Plan> findAllByPlanStatusOrderByDisplayOrderAsc(String planStatus);
}
