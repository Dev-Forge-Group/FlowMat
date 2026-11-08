package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** Current planning rate, separate from equipment details and from any actual execution cost snapshot. */
@Getter
@Setter
@Entity
@Table(name = "equipment_hourly_cost")
public class EquipmentHourlyCost {
    @Id private String equipmentId;
    @Column(precision = 14, scale = 4)
    private BigDecimal hourlyCost;
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
