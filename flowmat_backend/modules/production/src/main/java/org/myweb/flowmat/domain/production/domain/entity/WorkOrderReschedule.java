package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** Append-only evidence of an owner's schedule command; draft edits do not create this history. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "work_order_reschedule")
public class WorkOrderReschedule {
    @Id private String changeId;
    private String workOrderId;
    private UUID requestId;
    private OffsetDateTime previousPlannedStartAt;
    private OffsetDateTime previousPlannedEndAt;
    private OffsetDateTime plannedStartAt;
    private OffsetDateTime plannedEndAt;
    @Column(length = 1000) private String reason;
    private String changedBy;
    private OffsetDateTime changedAt;
}
