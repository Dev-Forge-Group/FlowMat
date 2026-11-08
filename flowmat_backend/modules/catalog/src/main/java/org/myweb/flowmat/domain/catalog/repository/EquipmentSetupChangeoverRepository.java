package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentSetupChangeover;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentSetupChangeoverRepository extends JpaRepository<EquipmentSetupChangeover,String> {
    List<EquipmentSetupChangeover> findAllByEquipmentIdAndDeletedYnOrderByPriorityAsc(String equipmentId,String deletedYn);
}
