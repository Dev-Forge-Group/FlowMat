package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "inventory_transaction")
public class InventoryTransaction extends BaseTimeEntity {

    @Id
    private String inventoryTransactionId;

    private String inventoryId;
    private String projectId;
    private String itemId;
    private String lotId;
    private String transactionType;
    private BigDecimal quantityDelta;
    private BigDecimal reservedDelta;
    private BigDecimal availableDelta;
    private BigDecimal quantityAfter;
    private BigDecimal reservedAfter;
    private BigDecimal availableAfter;
    private String referenceType;
    private String referenceId;
    private String note;
    private String createdBy;

    /** Client idempotency key; unique per project. Null for rows written before V17. */
    private String requestId;
}
