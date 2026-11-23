package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.PlanConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanConfirmationRepository extends JpaRepository<PlanConfirmation, PlanConfirmation.Key> {

    /** A confirmation makes its own work order, so there is at most one. */
    Optional<PlanConfirmation> findFirstByWorkOrderId(String workOrderId);

    List<PlanConfirmation> findAllByWorkflowIdOrderByCreatedAtDesc(String workflowId);
}
