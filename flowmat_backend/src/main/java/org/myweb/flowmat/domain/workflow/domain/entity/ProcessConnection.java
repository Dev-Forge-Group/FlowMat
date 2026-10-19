package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "process_connection")
public class ProcessConnection extends CreatedUpdatedAuditEntity {

    @Id
    private String connectionId;

    private String projectId;
    private String workflowId;
    private String fromProcessId;
    private String toProcessId;
    private String fromIoId;
    private String toIoId;
    private String itemId;
    private String sourceHandle;
    private String targetHandle;
    private String connectionType;
    private String connectionLabel;
    private BigDecimal flowRate;
    private String conditionExpr;
    private BigDecimal capacity;
    private String failurePolicy;
    private String unit;
    private BigDecimal delayTimeSec;
    /** elapsed counts all time, working only the work order's working time in production runs (ADR-022, V99). */
    private String delayBasis = "elapsed";
    private BigDecimal lossRate;
    private Integer priority;
    /** Y on an Exclusive gateway's default connection (ADR-006 decision 3, V85). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String defaultRouteYn = "N";

    public boolean isDefaultRoute() {
        return "Y".equals(defaultRouteYn);
    }

    /** A Split gateway's share (ADR-008 decision 2, V89): share (a percent), fixed (a quantity) or rest; null elsewhere. */
    private String splitKind;
    @Column(precision = 19, scale = 4)
    private BigDecimal splitValue;

    @Column(name = "version")
    private int version = 1;

    @Column(name = "version_nonce")
    private int versionNonce;
}
