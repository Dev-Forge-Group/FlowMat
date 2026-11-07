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
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "inventory")
public class Inventory extends CreatedUpdatedAuditEntity {

    @Id
    private String inventoryId;

    private String projectId;
    private String itemId;
    private String lotId;
    private BigDecimal quantity;
    private BigDecimal reservedQuantity;
    private BigDecimal availableQuantity;
    private String inventoryStatus;
    private String location;

    /** Reorder point: available stock below this is reported as "low". */
    @Column(precision = 14, scale = 4)
    private BigDecimal minThreshold;

    /** Optional ceiling: on-hand stock above this is reported as "over". */
    @Column(precision = 14, scale = 4)
    private BigDecimal maxThreshold;

    /** Optimistic lock; also bumped by the atomic quantity update in InventoryRepository. */
    @Version
    private Long version;

    /** When a stock count last covered this record, with or without a difference (docs/domain/stock-count.md "마지막 실사"). */
    private OffsetDateTime lastCheckedAt;
    private String lastCheckedBy;
}
