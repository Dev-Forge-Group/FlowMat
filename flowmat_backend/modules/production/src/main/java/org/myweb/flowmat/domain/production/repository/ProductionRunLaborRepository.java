package org.myweb.flowmat.domain.production.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunLabor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunLaborRepository extends JpaRepository<ProductionRunLabor, String> {
    Optional<ProductionRunLabor> findByProductionRunIdAndRequestId(String productionRunId, UUID requestId);
    List<ProductionRunLabor> findAllByProductionRunIdOrderByRecordedAtAscRunLaborIdAsc(String productionRunId);
    List<ProductionRunLabor> findAllByProductionRunIdInAndCancelledYnOrderByRecordedAtAscRunLaborIdAsc(
        Collection<String> productionRunIds, String cancelledYn);
    /** One person's records that count in a project, recorded in [from, to) (ADR-026 comparison). */
    List<ProductionRunLabor> findAllByProjectIdAndUserIdAndCancelledYnAndRecordedAtGreaterThanEqualAndRecordedAtLessThan(
        String projectId, String userId, String cancelledYn, OffsetDateTime from, OffsetDateTime to);
    /** One person's records with clock times that count in a project, started in [from, to) (ADR-028 decision 25). */
    List<ProductionRunLabor> findAllByProjectIdAndUserIdAndCancelledYnAndWorkedFromGreaterThanEqualAndWorkedFromLessThan(
        String projectId, String userId, String cancelledYn, OffsetDateTime from, OffsetDateTime to);
}
