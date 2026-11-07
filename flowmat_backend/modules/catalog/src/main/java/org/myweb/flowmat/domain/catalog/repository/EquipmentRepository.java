package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, String> {
    List<Equipment> findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(String projectId, String deletedYn);
    Optional<Equipment> findByEquipmentIdAndDeletedYn(String equipmentId, String deletedYn);
    boolean existsByProjectIdAndEquipmentCodeIgnoreCaseAndDeletedYn(String projectId, String equipmentCode, String deletedYn);
}
