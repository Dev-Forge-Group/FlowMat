package org.myweb.flowmat.domain.catalog.repository;

import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentHourlyCost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentHourlyCostRepository extends JpaRepository<EquipmentHourlyCost, String> {
}
