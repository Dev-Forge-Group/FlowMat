package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "work_order")
public class WorkOrder extends CreatedUpdatedAuditEntity {

    @Id
    private String workOrderId;

    private String projectId;
    private String workflowId;
    private String bomId;
    private String workOrderNumber;
    private String workOrderTitle;
    /** Lowercase {@link org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus} code. */
    private String workOrderStatus;
    private String priority;
    private String targetItemId;

    @Column(precision = 14, scale = 4)
    private BigDecimal targetQuantity;

    private OffsetDateTime plannedStartAt;
    private OffsetDateTime plannedEndAt;
    private OffsetDateTime actualStartAt;
    private OffsetDateTime actualEndAt;

    @Column(columnDefinition = "text")
    private String instruction;

    /** A link to the written work instruction (a PDF or any page), http or https only. V1 named the column for PDFs. */
    @Column(name = "pdf_url", length = 255)
    private String instructionUrl;

    @Column(length = 50)
    private String assignedTo;
    /** The equipment the order runs on (V32); readiness checks its time in the planned window. */
    private String equipmentId;
    private String issuedBy;
    private OffsetDateTime issuedAt;
    private String approvedBy;
    private OffsetDateTime approvedAt;
}
