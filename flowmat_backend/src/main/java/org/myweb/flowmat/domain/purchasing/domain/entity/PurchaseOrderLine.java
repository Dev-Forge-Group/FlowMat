package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** One item of a purchase order, in the item's stock unit (ADR-027 decision 5). */
@Getter
@Setter
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine {
    @Id
    private String lineId;
    private String purchaseOrderId;
    private Integer lineNo;
    private String itemId;
    private BigDecimal quantity;
    private BigDecimal receivedQuantity;
    private LocalDate expectedDate;
    @Column(length = 500)
    private String note;
    /** Per stock unit; null while not agreed (ADR-027 decision 9). */
    private BigDecimal unitPrice;
    /** The supplier's own code for the item, kept as the order is placed (PO45); null without one or on a draft. */
    @Column(length = 100)
    private String supplierItemCode;
    /** Sent back to be replaced on this line (PO46), so it is to come again; what was received counts it too. */
    private BigDecimal replacementQuantity = BigDecimal.ZERO;

    public BigDecimal remaining() {
        return quantity.add(replacementQuantity).subtract(receivedQuantity);
    }
}
