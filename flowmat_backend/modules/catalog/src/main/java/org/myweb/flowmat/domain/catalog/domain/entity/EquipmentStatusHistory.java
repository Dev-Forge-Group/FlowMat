package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One status change of a piece of equipment (docs/domain/equipment.md "상태 이력"); none before it when it was added. */
@Getter
@Setter
@Entity
@Table(name = "equipment_status_history")
public class EquipmentStatusHistory {

    @Id
    private String equipmentStatusHistoryId;

    private String equipmentId;
    private String previousStatus;
    private String equipmentStatus;
    private String note;
    private String changedBy;
    private OffsetDateTime changedAt;
}
