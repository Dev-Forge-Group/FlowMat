package org.myweb.flowmat.domain.workflow.editor.domain;

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
@Table(name = "workflow_editor_document")
public class WorkflowEditorDocument extends CreatedUpdatedAuditEntity {

    @Id
    @Column(name = "workflow_id")
    private String workflowId;

    private String projectId;

    @Column(name = "schema_version")
    private Integer schemaVersion = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "camera_json", columnDefinition = "jsonb")
    private String cameraJson = "{\"x\":0,\"y\":0,\"zoom\":1}";

    @Column(name = "next_element_seq")
    private Integer nextElementSeq = 1;

    @Column(name = "version")
    private int version = 1;

    @Column(name = "version_nonce")
    private long versionNonce = 1L;
}
