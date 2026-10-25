package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** What the supplier credited for a return (ADR-027 decision 19, V121). One per return, never changed. */
@Getter
@Setter
@Entity
@Table(name = "purchase_credit")
public class PurchaseCredit {
    @Id
    private String returnId;
    private String purchaseOrderId;
    private BigDecimal amount;
    /** The supplier's credit note number; null when none was given. */
    @Column(length = 100)
    private String creditNote;
    private String creditedBy;
    private OffsetDateTime creditedAt;
}
