package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * A planned move of one stock record's quantity to a place: a putaway to storage or a pick to a staging place
 * (docs/domain/warehouse-task.md). Doing it records an ordinary transfer.
 */
@Getter
@Setter
@Entity
@Table(name = "warehouse_task")
public class WarehouseTask {

    @Id
    private String taskId;

    private String projectId;

    /** WT-0001, WT-0002 ... per project. */
    private String taskNo;

    /** putaway or pick. */
    private String taskType;

    /** open, done or cancelled. */
    private String status;
    private String inventoryId;
    private String itemId;
    private String lotId;

    @Column(precision = 14, scale = 4)
    private BigDecimal quantity;

    /** Where the record was when the task was made. */
    private String fromLocation;
    private String toLocation;
    private String workOrderId;
    /** The reservation used by this pick; null for free-stock picks and putaways. */
    private String allocationId;
    private String note;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String finishedBy;
    private OffsetDateTime finishedAt;

    /** The transfer that did the task. */
    private String transferId;
    private String cancelReason;

    /** Who should do the task (W7, V49); null while no one is named. */
    private String assignedTo;
}
