package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "process")
public class Process extends CreatedUpdatedAuditEntity {

    @Id
    private String processId;

    private String projectId;
    private String workflowId;
    private String templateId;
    private String processName;
    private String processType;
    private String nodeType;
    private String processStatus;
    private String colorScheme;

    // Schema stores coordinates/sizes as numeric; columnDefinition keeps ddl-auto=validate in sync.
    @Column(name = "pos_x", columnDefinition = "numeric(10,2)")
    private Double posX;

    @Column(name = "pos_y", columnDefinition = "numeric(10,2)")
    private Double posY;

    @Column(columnDefinition = "numeric(8,2)")
    private Double width;
    @Column(columnDefinition = "numeric(8,2)")
    private Double height;
    private String processDesc;

    @Column(name = "version")
    private int version = 1;

    @Column(name = "version_nonce")
    private int versionNonce;

    // Execution policy (docs/domain/flow-run-execution-policy.md EP1); null uses the default behavior.
    private Integer timeoutSeconds;
    private Integer retryLimit;
    private Integer retryDelaySeconds;
    private String retryBackoff;
    private Integer maxRetryDelaySeconds;
    private Integer concurrencyLimit;
    private long executionPolicyVersion;

    /** Whether any execution policy value is set, so the node is listed in a published revision (EP3). */
    public boolean hasExecutionPolicy() {
        return timeoutSeconds != null || retryLimit != null || retryDelaySeconds != null || retryBackoff != null
            || maxRetryDelaySeconds != null || concurrencyLimit != null;
    }
}
