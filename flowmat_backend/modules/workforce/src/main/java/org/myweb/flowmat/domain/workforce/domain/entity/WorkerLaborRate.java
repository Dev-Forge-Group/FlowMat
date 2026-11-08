package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A worker's hourly rate in a project from a project date (docs/architecture/adr/ADR-025-worker-labor-cost.md, V103). Every
 * save is a new row; a rate removed before it started stays marked.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_labor_rate")
public class WorkerLaborRate {
    @Id
    private String rateId;
    private String projectId;
    private String userId;
    /** Per hour, 4 decimals; null is unknown, not zero. */
    @Column(precision = 14, scale = 4)
    private BigDecimal hourlyRate;
    /** The project date it applies from; null from the start. */
    private LocalDate effectiveFrom;
    private String reason;
    private String setBy;
    private OffsetDateTime setAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String removedYn;
    private String removedBy;
    private OffsetDateTime removedAt;

    public boolean isRemoved() {
        return "Y".equals(removedYn);
    }
}
