package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentHourlyCostHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentHourlyCostHistoryRepository extends JpaRepository<EquipmentHourlyCostHistory, String> {
    List<EquipmentHourlyCostHistory> findAllByEquipmentIdOrderByChangedAtDescEquipmentHourlyCostHistoryIdDesc(String equipmentId);
}
