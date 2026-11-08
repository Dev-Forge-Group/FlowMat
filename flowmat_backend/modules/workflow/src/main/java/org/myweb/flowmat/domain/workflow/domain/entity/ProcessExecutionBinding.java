package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "process_execution_binding")
public class ProcessExecutionBinding {
    @Id
    private String processId;
    private String profileVersionId;
    @Column(length = 100)
    private String operationId;
    private String outputPortId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String requestMapping;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String responseMapping;
    private long bindingVersion;
}
