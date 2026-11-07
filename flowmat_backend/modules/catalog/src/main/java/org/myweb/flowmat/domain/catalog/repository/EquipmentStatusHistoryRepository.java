package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusHistoryRepository extends JpaRepository<EquipmentStatusHistory, String> {

    List<EquipmentStatusHistory> findAllByEquipmentIdOrderByChangedAtDesc(String equipmentId);
}
