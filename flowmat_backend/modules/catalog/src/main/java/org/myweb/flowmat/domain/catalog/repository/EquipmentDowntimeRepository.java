package org.myweb.flowmat.domain.catalog.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentDowntime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EquipmentDowntimeRepository extends JpaRepository<EquipmentDowntime, String> {

    List<EquipmentDowntime> findAllByEquipmentIdAndDeletedYnOrderByStartsAtDesc(String equipmentId, String deletedYn);

    Optional<EquipmentDowntime> findByDowntimeIdAndDeletedYn(String downtimeId, String deletedYn);

    /** Live downtime that overlaps [from, to). */
    @Query("""
        select d from EquipmentDowntime d
        where d.equipmentId = :equipmentId and d.deletedYn = 'N' and d.startsAt < :to and d.endsAt > :from
        order by d.startsAt
        """)
    List<EquipmentDowntime> findOverlapping(
        @Param("equipmentId") String equipmentId,
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to
    );
}
