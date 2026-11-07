package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** A period the equipment cannot work: planned maintenance, a breakdown or anything else (docs/domain/equipment-schedule.md). */
@Getter
@Setter
@Entity
@Table(name = "equipment_downtime")
public class EquipmentDowntime extends CreatedUpdatedAuditEntity {

    @Id
    private String downtimeId;

    private String projectId;
    private String equipmentId;
    /** maintenance, breakdown or other. */
    private String downtimeType;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private String reason;
}
