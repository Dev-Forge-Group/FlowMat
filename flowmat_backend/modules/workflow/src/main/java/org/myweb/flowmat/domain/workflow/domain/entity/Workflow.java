package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "workflow")
public class Workflow extends CreatedUpdatedAuditEntity {

    @Id
    private String workflowId;

    private String projectId;
    private String workflowName;
    private String workflowDesc;
    private String workflowType;
    private String workflowStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String canvasSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String simulationConfig;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String lockedYn;
    private String lockedBy;
}
