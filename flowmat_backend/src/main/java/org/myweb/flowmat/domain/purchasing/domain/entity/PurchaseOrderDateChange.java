package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** An expected date moved on an order being received, and why (ADR-027 decision 11, V110). Never changed. */
@Getter
@Setter
@Entity
@Table(name = "purchase_order_date_change")
public class PurchaseOrderDateChange {
    @Id
    private String changeId;
    private String purchaseOrderId;
    /** Null for the order's own expected date. */
    private String lineId;
    /** Null when there was none, or when it was cleared. */
    private LocalDate previousDate;
    private LocalDate expectedDate;
    @Column(length = 500)
    private String reason;
    private String changedBy;
    private OffsetDateTime changedAt;
}
