package org.myweb.flowmat.domain.integration.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One run of a job, by hand or on its schedule, and what happened to each item (ADR-038 decision 4). */
@Getter
@Setter
@Entity
@Table(name = "integration_run")
public class IntegrationRun {

    @Id
    private String runId;
    private String jobId;
    private String projectId;
    /** manual or schedule. */
    private String runTrigger;
    private OffsetDateTime scheduledFor;
    /** running, succeeded, partial, failed or outcome_unknown. */
    private String status;
    private String runBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private Integer rowsTotal;
    private Integer rowsApplied;
    private Integer rowsLeftOut;
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", columnDefinition = "jsonb")
    private String itemsJson = "[]";
}
