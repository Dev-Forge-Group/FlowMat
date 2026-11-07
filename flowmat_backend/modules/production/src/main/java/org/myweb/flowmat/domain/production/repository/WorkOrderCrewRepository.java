package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderCrewRepository extends JpaRepository<WorkOrderCrew, String> {

    List<WorkOrderCrew> findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(String workOrderId);

    List<WorkOrderCrew> findAllByWorkOrderIdIn(Collection<String> workOrderIds);

    List<WorkOrderCrew> findAllByUserIdIn(Collection<String> userIds);
}
