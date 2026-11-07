package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One actual setup of a run with the equipment's rate when it was recorded (docs/domain/equipment-setup-cost.md AS1-AS6).
 * Rows are never deleted; a mistaken one is cancelled with a reason.
 */
@Getter
@Setter
@Entity
@Table(name = "production_run_setup")
public class ProductionRunSetup {
    @Id private String runSetupId;
    private String productionRunId;
    private String projectId;
    private String equipmentId;
    private Integer setupMinutes;
    /** The equipment's hourly rate when recorded; null when it had none. */
    private BigDecimal hourlyCost;
    private Long hourlyCostVersion;
    /** setupMinutes × hourlyCost / 60, 4 decimals; null with an unknown rate. */
    private BigDecimal setupCost;
    private UUID requestId;
    @Column(length = 500) private String note;
    private String recordedBy;
    private OffsetDateTime recordedAt;
    @JdbcTypeCode(SqlTypes.CHAR) private String cancelledYn;
    private String cancelledBy;
    private OffsetDateTime cancelledAt;
    @Column(length = 500) private String cancelReason;
    /** recorded (rate when recorded), historical (rate at the run's finish, by a correction) or estimated (AS8, AS10). */
    private String rateBasis = "recorded";

    public boolean isCancelled() {
        return "Y".equals(cancelledYn);
    }
}
