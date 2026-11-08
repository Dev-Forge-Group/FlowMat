package org.myweb.flowmat.domain.catalog.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDayOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentDayOverrideRepository extends JpaRepository<EquipmentDayOverride, String> {

    List<EquipmentDayOverride> findAllByEquipmentIdOrderByOverrideDateAsc(String equipmentId);

    List<EquipmentDayOverride> findAllByEquipmentIdAndOverrideDateBetween(String equipmentId, LocalDate from, LocalDate to);

    Optional<EquipmentDayOverride> findByEquipmentIdAndOverrideDate(String equipmentId, LocalDate overrideDate);
}
