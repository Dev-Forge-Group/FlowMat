package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One person's planned worker minutes on a process of a work order's step plan (docs/domain/worker-calendar.md LB29, V131). */
@Getter
@Setter
@Entity
@Table(name = "work_order_step_plan_person")
public class WorkOrderStepPlanPerson {
    @Id
    private String planPersonId;
    /** The process's plan row; the person goes with it. */
    private String planId;
    private String userId;
    private Integer sortOrder;
    private Integer plannedMinutes;
}
