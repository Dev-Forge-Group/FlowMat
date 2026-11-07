package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunEquipmentTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunEquipmentTimeRepository extends JpaRepository<ProductionRunEquipmentTime, String> {
    Optional<ProductionRunEquipmentTime> findByProductionRunIdAndRequestId(String productionRunId, UUID requestId);
    List<ProductionRunEquipmentTime> findAllByProductionRunIdOrderByRecordedAtAscRunEquipmentTimeIdAsc(String productionRunId);
}
