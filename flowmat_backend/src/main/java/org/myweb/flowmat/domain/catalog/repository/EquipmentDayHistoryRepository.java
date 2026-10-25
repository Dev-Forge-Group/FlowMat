package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentDayHistoryRepository extends JpaRepository<EquipmentDayHistory, String> {
    List<EquipmentDayHistory> findTop100ByEquipmentIdOrderBySavedAtDescHistoryIdDesc(String equipmentId);
}
