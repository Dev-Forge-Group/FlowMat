package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentChangeover;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentChangeoverRepository extends JpaRepository<EquipmentChangeover, String> {

    List<EquipmentChangeover> findAllByEquipmentIdAndDeletedYn(String equipmentId, String deletedYn);

    Optional<EquipmentChangeover> findByChangeoverIdAndDeletedYn(String changeoverId, String deletedYn);
}
