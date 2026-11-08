package org.myweb.flowmat.domain.quality.repository;

import java.util.List;
import org.myweb.flowmat.domain.quality.domain.entity.QualityInspection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityInspectionRepository extends JpaRepository<QualityInspection, String> {

    List<QualityInspection> findAllByProjectIdOrderByInspectedAtDesc(String projectId);

    List<QualityInspection> findAllByProductionRunIdOrderByInspectedAtDesc(String productionRunId);

    List<QualityInspection> findAllByLotIdOrderByInspectedAtDesc(String lotId);
}
