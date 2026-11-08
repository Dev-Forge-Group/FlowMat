package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One save or removal of a worker's schedule version with the shifts and rota it held then
 * (docs/architecture/adr/ADR-024-version-save-history.md, V101). The version keeps only its last save; these rows keep each.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_schedule_history")
public class WorkerScheduleHistory {
    @Id
    private String historyId;
    private String projectId;
    private String userId;
    private String scheduleId;
    private LocalDate effectiveFrom;
    /** saved or removed. */
    private String action;
    private String reason;
    /** The shifts then: [{shiftStart, shiftEnd, workDays, rotaWeek}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shifts_json", columnDefinition = "jsonb")
    private String shiftsJson;
    /** The rota then; null without one. */
    private Integer rotaWeeks;
    private LocalDate rotaStartsOn;
    private String savedBy;
    private OffsetDateTime savedAt;
}
