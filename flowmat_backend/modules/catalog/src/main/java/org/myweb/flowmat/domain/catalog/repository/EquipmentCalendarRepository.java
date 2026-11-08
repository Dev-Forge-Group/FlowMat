package org.myweb.flowmat.domain.catalog.repository;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentCalendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentCalendarRepository extends JpaRepository<EquipmentCalendar, String> {

    /** A calendar can be empty, so serialize replacements on the equipment key rather than existing shift rows. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext('equipment-calendar|' || :id))", nativeQuery = true)
    Integer lockCalendar(@Param("id") String equipmentId);

    /** The equipment's shifts, earliest start first. */
    List<EquipmentCalendar> findAllByEquipmentIdOrderByShiftStartAscShiftIdAsc(String equipmentId);
    /** One calendar version's shifts, earliest start first. */
    List<EquipmentCalendar> findAllByScheduleIdOrderByShiftStartAscShiftIdAsc(String scheduleId);
}
