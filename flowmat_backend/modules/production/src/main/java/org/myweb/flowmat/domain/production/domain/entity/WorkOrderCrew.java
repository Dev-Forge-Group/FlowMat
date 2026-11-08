package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One person a work order needs (docs/domain/worker-calendar.md WC11, V81). */
@Getter
@Setter
@Entity
@Table(name = "work_order_crew")
public class WorkOrderCrew {

    @Id
    private String crewId;

    private String workOrderId;
    private String userId;
    /** The part of their working time in the order's window the order needs, 1 to 100 (WC36, V83). */
    private Integer sharePercent;
    private String addedBy;
    private OffsetDateTime addedAt;
}
