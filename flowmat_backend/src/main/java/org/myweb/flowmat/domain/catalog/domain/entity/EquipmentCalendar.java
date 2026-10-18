package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One shift a piece of equipment works (docs/domain/equipment-schedule.md); a calendar is all the equipment's shifts. A
 * shift that ends at or before its start runs past midnight, and equal times mean the whole day. Equipment without a
 * shift is always available.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_calendar")
public class EquipmentCalendar {

    @Id
    private String shiftId;

    private String equipmentId;

    private LocalTime shiftStart;
    private LocalTime shiftEnd;

    /** ISO days of the week the shift starts on, such as "1,2,3,4,5" for Monday to Friday. */
    private String workDays;
    /** The calendar version it belongs to (V94, docs/architecture/adr/ADR-014-equipment-calendar-history.md). */
    private String scheduleId;

    private String updatedBy;
    private OffsetDateTime updatedAt;
}
