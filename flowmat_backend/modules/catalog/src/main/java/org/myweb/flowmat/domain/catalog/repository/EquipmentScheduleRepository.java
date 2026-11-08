package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentScheduleRepository extends JpaRepository<EquipmentSchedule, String> {
    List<EquipmentSchedule> findAllByEquipmentId(String equipmentId);
    Optional<EquipmentSchedule> findByScheduleIdAndEquipmentId(String scheduleId, String equipmentId);
}
