package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One approver's approval of a draft as it was (ADR-027 decision 21, V122); changing the draft removes them all. */
@Getter
@Setter
@Entity
@Table(name = "purchase_order_approval")
public class PurchaseOrderApproval {
    @Id
    private String approvalId;
    private String purchaseOrderId;
    private String approverId;
    private OffsetDateTime approvedAt;
}
