package org.myweb.flowmat.domain.catalog.repository;

import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayHistory;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Search-only access; the latest-100 list keeps its own repository (as the version history search). */
public interface EquipmentDayHistorySearchRepository extends Repository<EquipmentDayHistory, String>, JpaSpecificationExecutor<EquipmentDayHistory> {
    Optional<EquipmentDayHistory> findByHistoryIdAndEquipmentId(String historyId, String equipmentId);
}
