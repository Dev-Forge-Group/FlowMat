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
@Table(name = "flow_run")
public class FlowRun {

    @Id
    private String flowRunId;
    private String projectId;
    private String workflowId;
    private String workflowRevisionId;
    private String productionRunId;
    private String runType;
    private String executionMode = "manual";
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", columnDefinition = "jsonb")
    private String inputPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_payload", columnDefinition = "jsonb")
    private String outputPayload;

    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private String requestedBy;
}
