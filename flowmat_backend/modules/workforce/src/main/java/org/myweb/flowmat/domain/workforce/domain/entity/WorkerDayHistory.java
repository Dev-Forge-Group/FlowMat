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
 * One save or removal of a worker's own dates or day off with what it held then
 * (docs/architecture/adr/ADR-024-version-save-history.md decisions 7-12, V102). The dates keep only their last save.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_day_history")
public class WorkerDayHistory {
    @Id
    private String historyId;
    private String projectId;
    private String userId;
    /** day (a date's own shifts) or day_off. */
    private String changeKind;
    /** saved or removed. */
    private String action;
    private LocalDate firstDate;
    private LocalDate lastDate;
    /** A day change's dates then: [{date, shifts: [{shiftStart, shiftEnd}], reason}]; null for a day off. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "days_json", columnDefinition = "jsonb")
    private String daysJson;
    private String dayOffId;
    private String reason;
    /** The worker the dates were copied from; null otherwise. */
    private String copiedFrom;
    private String savedBy;
    private OffsetDateTime savedAt;
}
