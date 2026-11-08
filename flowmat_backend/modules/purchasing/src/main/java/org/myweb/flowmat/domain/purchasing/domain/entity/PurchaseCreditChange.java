package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** A change to a supplier credit kept for a return, and why (ADR-027 decision 22, V123). Never changed itself. */
@Getter
@Setter
@Entity
@Table(name = "purchase_credit_change")
public class PurchaseCreditChange {
    @Id
    private String changeId;
    private String returnId;
    private String purchaseOrderId;
    /** Null: withdrawn, so nothing is credited and the return is owed again. */
    private BigDecimal amount;
    @Column(length = 100)
    private String creditNote;
    @Column(length = 500)
    private String reason;
    private String changedBy;
    private OffsetDateTime changedAt;
}
