package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/**
 * How long the equipment takes to switch from making one item to another (docs/domain/equipment-changeover.md). A null
 * side means any item.
 */
@Getter
@Setter
@Entity
@Table(name = "equipment_changeover")
public class EquipmentChangeover extends CreatedUpdatedAuditEntity {

    @Id
    private String changeoverId;

    private String projectId;
    private String equipmentId;
    private String fromItemId;
    private String toItemId;
    private Integer changeoverMinutes;
    private String note;
}
