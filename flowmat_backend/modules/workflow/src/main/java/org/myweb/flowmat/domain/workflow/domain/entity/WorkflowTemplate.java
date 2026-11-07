package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "workflow_template")
public class WorkflowTemplate extends BaseTimeEntity {

    @Id
    private String templateId;

    private String templateName;
    private String templateCategory;
    private String templateType;
    private String iconKey;
    @Column(columnDefinition = "numeric(8,2)")
    private Double defaultWidth;
    @Column(columnDefinition = "numeric(8,2)")
    private Double defaultHeight;
    private String defaultDesc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String defaultConfig;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String publicYn;
    private Integer sortOrder;
}
