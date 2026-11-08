package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentScheduleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentScheduleHistoryRepository extends JpaRepository<EquipmentScheduleHistory, String> {
    List<EquipmentScheduleHistory> findTop100ByEquipmentIdOrderBySavedAtDescHistoryIdDesc(String equipmentId);
}
