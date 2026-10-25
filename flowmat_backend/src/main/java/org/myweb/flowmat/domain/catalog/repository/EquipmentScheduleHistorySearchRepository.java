package org.myweb.flowmat.domain.catalog.repository;

import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentScheduleHistory;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Search-only access; retaining the existing repository keeps its latest-100 API unchanged. */
public interface EquipmentScheduleHistorySearchRepository extends Repository<EquipmentScheduleHistory, String>, JpaSpecificationExecutor<EquipmentScheduleHistory> {
    Optional<EquipmentScheduleHistory> findByHistoryIdAndEquipmentId(String historyId, String equipmentId);
}
