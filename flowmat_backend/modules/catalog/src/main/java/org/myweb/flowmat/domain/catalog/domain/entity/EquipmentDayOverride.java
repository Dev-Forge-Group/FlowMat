package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * A piece of equipment's shifts for one date in place of its calendar's (docs/domain/equipment-schedule.md "날짜별 교대").
 * {@code shifts} is "06:00-14:00,14:00-18:00"; empty means the equipment does not work that day.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_day_override")
public class EquipmentDayOverride {

    @Id
    private String overrideId;

    private String equipmentId;
    private LocalDate overrideDate;
    private String shifts;
    private String reason;

    private String updatedBy;
    private OffsetDateTime updatedAt;
}
