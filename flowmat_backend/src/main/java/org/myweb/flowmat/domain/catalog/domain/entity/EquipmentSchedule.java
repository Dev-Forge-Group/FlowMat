package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One version of a piece of equipment's calendar (docs/architecture/adr/ADR-014-equipment-calendar-history.md, V94): it
 * applies from {@code effectiveFrom} until the next version starts, and without shifts the equipment runs around the
 * clock from then. Null is the version from the start, which the shifts saved before V94 became. One version a day.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_schedule")
public class EquipmentSchedule {
    @Id
    private String scheduleId;
    private String equipmentId;
    private LocalDate effectiveFrom;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
