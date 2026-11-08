package org.myweb.flowmat.domain.production.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunCorrectionRepository extends JpaRepository<ProductionRunCorrection, String> {

    List<ProductionRunCorrection> findAllByProductionRunIdOrderByCorrectionNoDesc(String productionRunId);

    Optional<ProductionRunCorrection> findTopByProductionRunIdOrderByCorrectionNoDesc(String productionRunId);

    boolean existsByProductionRunIdAndStatus(String productionRunId, String status);
}
