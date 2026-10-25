package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Stock received on a purchase order line, and the stock record it went into (ADR-027 decision 6). */
@Getter
@Setter
@Entity
@Table(name = "purchase_receipt")
public class PurchaseReceipt {
    @Id
    private String receiptId;
    private String purchaseOrderId;
    private String lineId;
    /** Shared by the lines received together. */
    private UUID requestId;
    private BigDecimal quantity;
    private String inventoryId;
    private String location;
    private String lotNo;
    private String receivedBy;
    private OffsetDateTime receivedAt;
}
