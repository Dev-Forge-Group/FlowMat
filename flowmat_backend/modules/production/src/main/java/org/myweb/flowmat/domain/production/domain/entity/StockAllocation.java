package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * A quantity of one stock record reserved for one work order (docs/domain/stock-allocation.md). What the order's runs take
 * from the record is consumed, what is given back is released; it closes when nothing is left.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_allocation")
public class StockAllocation {

    @Id
    private String allocationId;

    private String projectId;
    private String workOrderId;
    private String inventoryId;
    private String itemId;
    private String lotId;

    @Column(precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 14, scale = 4)
    private BigDecimal consumedQuantity;

    @Column(precision = 14, scale = 4)
    private BigDecimal releasedQuantity;

    private String status;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime closedAt;

    /** Still reserved: allocated less consumed and released. */
    public BigDecimal remaining() {
        return quantity.subtract(consumedQuantity).subtract(releasedQuantity);
    }
}
