package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_count_plan_line")
public class InventoryCountPlanLine {
    @Id private String lineId;
    private String planId;
    private String inventoryId;
    private String itemId;
    private String lotId;
    @Column(length = 100) private String location;
    @Column(updatable = false, precision = 14, scale = 4) private BigDecimal baselineQuantity;
    @Column(updatable = false) private Long baselineVersion;
    @Column(precision = 14, scale = 4) private BigDecimal checkpointQuantity;
    private Long checkpointVersion;
    @Column(precision = 14, scale = 4) private BigDecimal countedQuantity;
    private boolean requiresRecount;
    private String countedBy;
    private OffsetDateTime countedAt;
    @Version private Long entryVersion;
}
