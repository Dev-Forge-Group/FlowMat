package org.myweb.flowmat.domain.quality.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.quality.domain.entity.DefectLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DefectLogRepository extends JpaRepository<DefectLog, String> {

    List<DefectLog> findAllByProjectIdOrderByLoggedAtDesc(String projectId);

    List<DefectLog> findAllByProductionRunIdOrderByLoggedAtDesc(String productionRunId);

    List<DefectLog> findAllByLotIdOrderByLoggedAtDesc(String lotId);

    /** Locks the defect so two people resolving it at once cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DefectLog d where d.defectLogId = :id")
    Optional<DefectLog> findForUpdate(@Param("id") String defectLogId);
}
