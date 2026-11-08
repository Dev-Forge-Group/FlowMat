package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunSetup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunSetupRepository extends JpaRepository<ProductionRunSetup, String> {
    Optional<ProductionRunSetup> findByProductionRunIdAndRequestId(String productionRunId, UUID requestId);
    List<ProductionRunSetup> findAllByProductionRunIdOrderByRecordedAtAscRunSetupIdAsc(String productionRunId);
}
