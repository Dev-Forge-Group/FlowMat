package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Stock received on a purchase order line and sent back to the supplier, and why (ADR-027 decision 12, V111). Never changed. */
@Getter
@Setter
@Entity
@Table(name = "purchase_return")
public class PurchaseReturn {
    @Id
    private String returnId;
    private String purchaseOrderId;
    private String lineId;
    private UUID requestId;
    private BigDecimal quantity;
    /** The stock record it left, with its place and LOT then. */
    private String inventoryId;
    private String location;
    private String lotNo;
    @Column(length = 500)
    private String reason;
    private String returnedBy;
    private OffsetDateTime returnedAt;
    /** The NCR that decided to send it back and its number then (PO42); both null when none. */
    private String nonconformityId;
    private String ncrNo;
    /** "Y": the supplier replaces it on the same line (PO46). "N" unless set, like the column. */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String replacementYn = "N";
}
