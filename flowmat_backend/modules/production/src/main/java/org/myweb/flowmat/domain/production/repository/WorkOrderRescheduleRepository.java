package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderReschedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRescheduleRepository extends JpaRepository<WorkOrderReschedule, String> {
    Optional<WorkOrderReschedule> findByWorkOrderIdAndRequestId(String workOrderId, UUID requestId);
    List<WorkOrderReschedule> findAllByWorkOrderIdOrderByChangedAtDescChangeIdDesc(String workOrderId);
}
