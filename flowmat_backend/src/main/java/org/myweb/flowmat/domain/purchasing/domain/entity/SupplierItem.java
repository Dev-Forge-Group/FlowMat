package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** The supplier an item is usually bought from (docs/architecture/adr/ADR-027-purchasing.md decision 13, V112); one per item. */
@Getter
@Setter
@Entity
@Table(name = "supplier_item")
public class SupplierItem {
    @Id
    private String supplierItemId;
    private String projectId;
    private String itemId;
    private String supplierId;
    /** What the supplier calls the item, as typed. */
    @Column(length = 100)
    private String supplierItemCode;
    /** The supplier's price per stock unit (PO38); null when not agreed. */
    private BigDecimal unitPrice;
    /** One up with every change; a change names the version it started from. */
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
