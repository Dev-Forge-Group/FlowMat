package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** A correction voucher for a finished run (docs/domain/production-run-correction.md). The run's own rows never change. */
@Getter
@Setter
@Entity
@Table(name = "production_run_correction")
public class ProductionRunCorrection {

    public static final String PENDING_APPROVAL = "pending_approval";
    public static final String APPLIED = "applied";
    public static final String REJECTED = "rejected";

    @Id
    private String productionRunCorrectionId;

    private String projectId;
    private String productionRunId;
    private Integer correctionNo;
    private String status;
    private String reason;
    private String requestedBy;
    private OffsetDateTime requestedAt;
    private String decidedBy;
    private OffsetDateTime decidedAt;
    private String decisionNote;
    private OffsetDateTime appliedAt;

    public boolean isPending() {
        return PENDING_APPROVAL.equals(status);
    }
}
