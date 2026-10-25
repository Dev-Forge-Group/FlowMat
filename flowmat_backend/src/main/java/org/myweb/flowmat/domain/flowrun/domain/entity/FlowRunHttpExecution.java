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

/** Durable transmission claim and sanitized evidence. JSON is intentionally absent from toString. */
@Getter
@Setter
@Entity
@Table(name = "flow_run_http_execution")
public class FlowRunHttpExecution {
    @Id private String executionId;
    private String flowRunId;
    private String stepId;
    private String attemptId;
    private String requestId;
    private String claimToken;
    private String profileVersionId;
    private String operationId;
    private String requestHash;
    private String transportStatus;
    private String applyStatus;
    private Integer httpStatus;
    private String errorCode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String outputJson;
    private String requestedBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    @Override public String toString() { return "FlowRunHttpExecution[executionId=" + executionId + ", transportStatus=" + transportStatus + "]"; }
}
