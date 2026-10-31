package org.myweb.flowmat.domain.production.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductionRunRepository extends JpaRepository<ProductionRun, String> {

    /** Serializes a run's recordings, checklist changes, finishing and corrections under the same row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductionRun r where r.productionRunId = :id and r.deletedYn = 'N'")
    Optional<ProductionRun> findForUpdate(@Param("id") String productionRunId);

    List<ProductionRun> findAllByWorkflowIdAndDeletedYnOrderByCreatedAtDesc(String workflowId, String deletedYn);

    Optional<ProductionRun> findByProductionRunIdAndDeletedYn(String productionRunId, String deletedYn);

    List<ProductionRun> findAllByWorkOrderIdInAndDeletedYn(Collection<String> workOrderIds, String deletedYn);

    /** A project's runs that ended in [from, to) (ADR-028 decision 28). */
    List<ProductionRun> findAllByProjectIdAndDeletedYnAndActualEndAtGreaterThanEqualAndActualEndAtLessThan(
        String projectId, String deletedYn, OffsetDateTime from, OffsetDateTime to);
}
