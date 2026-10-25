package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** The worker minutes planned for one process of a work order's workflow (docs/domain/worker-calendar.md LB23, V124). */
@Getter
@Setter
@Entity
@Table(name = "work_order_step_plan")
public class WorkOrderStepPlan {
    @Id
    private String planId;
    private String workOrderId;
    /** The workflow's process, as a run's steps name it (LB18). */
    private String nodeId;
    /** The process's name as it was planned, kept should it be renamed or removed. */
    private String nodeName;
    private Integer sortOrder;
    private Integer plannedMinutes;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
