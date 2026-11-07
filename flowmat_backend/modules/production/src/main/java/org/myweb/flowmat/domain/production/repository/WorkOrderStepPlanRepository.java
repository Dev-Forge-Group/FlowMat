package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderStepPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStepPlanRepository extends JpaRepository<WorkOrderStepPlan, String> {
    List<WorkOrderStepPlan> findAllByWorkOrderIdOrderBySortOrderAsc(String workOrderId);
}
