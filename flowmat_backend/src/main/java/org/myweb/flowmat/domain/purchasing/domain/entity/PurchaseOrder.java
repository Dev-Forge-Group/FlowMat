package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * What a project buys from a supplier (docs/architecture/adr/ADR-027-purchasing.md, V106). Never deleted: cancelled or closed
 * with a reason.
 */
@Getter
@Setter
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder {
    @Id
    private String purchaseOrderId;
    private String projectId;
    /** PO-0001, per project, never reused. */
    private String orderNumber;
    @Column(length = 100)
    private String supplierName;
    /** draft, ordered, received, closed or cancelled. */
    private String status;
    private LocalDate expectedDate;
    /** Where it is to arrive (PO39); null when not said. */
    @Column(length = 100)
    private String deliverTo;
    @Column(length = 500)
    private String note;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String updatedBy;
    private OffsetDateTime updatedAt;
    private String orderedBy;
    private OffsetDateTime orderedAt;
    /** Who received the last of it, closed it or cancelled it, and when. */
    private String endedBy;
    private OffsetDateTime endedAt;
    @Column(length = 500)
    private String endReason;
    /** The project owner who approved the draft as it is, and when (PO34); a change to the draft clears both. */
    private String approvedBy;
    private OffsetDateTime approvedAt;
}
