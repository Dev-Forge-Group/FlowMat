package org.myweb.flowmat.domain.flowrun.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "flow_run_step")
public class FlowRunStep {
    @Id private String stepId;
    private String flowRunId;
    private String nodeId;
    private String sourceConnectionId;
    private String sourceStepId;
    private String status;
    @Column(name = "sequence_no") private Integer sequenceNo;
    private OffsetDateTime scheduledAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", columnDefinition = "jsonb")
    private String inputSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_snapshot", columnDefinition = "jsonb")
    private String outputSnapshot;
    private String errorCode;
    private String errorMessage;
}
