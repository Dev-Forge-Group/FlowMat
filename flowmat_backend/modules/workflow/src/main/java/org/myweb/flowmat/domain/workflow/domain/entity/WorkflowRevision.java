package org.myweb.flowmat.domain.workflow.domain.entity;

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
@Table(name = "workflow_revision")
public class WorkflowRevision {

    @Id
    private String workflowRevisionId;
    private String workflowId;
    private Integer revisionNo;
    private String status;
    private Integer schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    private String snapshotJson;

    private String publishedBy;
    private OffsetDateTime publishedAt;
    private String retiredBy;
    private OffsetDateTime retiredAt;
}
