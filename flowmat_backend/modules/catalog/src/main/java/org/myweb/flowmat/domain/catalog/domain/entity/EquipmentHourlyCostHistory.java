package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** One change of an equipment's hourly rate (docs/domain/equipment-setup-cost.md AS9); null is unknown, zero is known. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "equipment_hourly_cost_history")
public class EquipmentHourlyCostHistory {
    @Id private String equipmentHourlyCostHistoryId;
    private String equipmentId;
    private BigDecimal previousHourlyCost;
    private BigDecimal hourlyCost;
    private long version;
    private String changedBy;
    private OffsetDateTime changedAt;
}
