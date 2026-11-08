package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Time a person worked on a run (docs/domain/worker-calendar.md LB1-LB4, V96). Rows are never deleted; a mistaken one is
 * cancelled with a reason.
 */
@Getter
@Setter
@Entity
@Table(name = "production_run_labor")
public class ProductionRunLabor {
    @Id private String runLaborId;
    private String productionRunId;
    private String projectId;
    private String userId;
    private Integer workedMinutes;
    private UUID requestId;
    @Column(length = 500) private String note;
    /** The process step of the run it was worked on; null for the whole run (LB13, V98). */
    private String stepId;
    /** When the worked time started and ended, both or neither (LB31, V132); null when only its minutes were recorded. */
    private OffsetDateTime workedFrom;
    private OffsetDateTime workedTo;
    private String recordedBy;
    private OffsetDateTime recordedAt;
    @JdbcTypeCode(SqlTypes.CHAR) private String cancelledYn;
    private String cancelledBy;
    private OffsetDateTime cancelledAt;
    @Column(length = 500) private String cancelReason;

    public boolean isCancelled() {
        return "Y".equals(cancelledYn);
    }
}
