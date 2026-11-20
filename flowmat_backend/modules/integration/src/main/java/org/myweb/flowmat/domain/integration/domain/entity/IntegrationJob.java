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
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** A repeatable integration with one connector operation (docs/architecture/adr/ADR-038-integration-jobs.md). */
@Getter
@Setter
@Entity
@Table(name = "integration_job")
public class IntegrationJob extends CreatedUpdatedAuditEntity {

    @Id
    private String jobId;
    private String projectId;
    private String name;
    /** pull_items, pull_stock or push_stock. */
    private String kind;
    private String profileVersionId;
    /** The operation that reads (pull) or sends (push). */
    private String operationId;
    /** push_stock only: the operation that reads the target back. */
    private String verifyOperationId;

    /** {"rowsAt": JSON pointer, "row": mapping} for a pull. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapping_json", columnDefinition = "jsonb")
    private String mappingJson;

    /** pull_stock: written on every receipt into an existing record, as the spreadsheet import's note is. */
    private String note;
    private String scheduleCron;
    private boolean scheduleEnabled;
    private OffsetDateTime nextRunAt;
    private String runAs;
    private int failuresInRow;
    private String pausedReason;
    private int version = 1;
}
