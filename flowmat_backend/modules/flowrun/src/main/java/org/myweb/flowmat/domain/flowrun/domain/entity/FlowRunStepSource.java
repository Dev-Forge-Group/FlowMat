package org.myweb.flowmat.domain.flowrun.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One arrival at a Join step (docs/architecture/adr/ADR-007-join-gateway.md decision 8, V88): the step and connection it
 * came by, numbered in arrival order.
 */
@Getter
@Setter
@Entity
@Table(name = "flow_run_step_source")
public class FlowRunStepSource {
    @Id private String stepSourceId;
    private String flowRunId;
    private String stepId;
    private Integer arrivalNo;
    private String sourceStepId;
    private String connectionId;
    private OffsetDateTime arrivedAt;
}
