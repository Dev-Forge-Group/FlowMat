package org.myweb.flowmat.domain.production.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunCorrectionLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionRunCorrectionLineRepository extends JpaRepository<ProductionRunCorrectionLine, String> {

    List<ProductionRunCorrectionLine> findAllByProductionRunCorrectionIdOrderByLineNoAsc(String productionRunCorrectionId);

    List<ProductionRunCorrectionLine> findAllByProductionRunCorrectionIdInOrderByLineNoAsc(Collection<String> correctionIds);

    /** The lines that added these worked-time records (ADR-025: they are priced at the original end of the run). */
    List<ProductionRunCorrectionLine> findAllByCreatedRunLaborIdIn(Collection<String> runLaborIds);
}
