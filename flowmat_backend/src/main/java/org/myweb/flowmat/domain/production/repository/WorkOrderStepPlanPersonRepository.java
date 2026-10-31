package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderStepPlanPerson;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStepPlanPersonRepository extends JpaRepository<WorkOrderStepPlanPerson, String> {
    List<WorkOrderStepPlanPerson> findAllByPlanIdInOrderBySortOrderAsc(Collection<String> planIds);
}
