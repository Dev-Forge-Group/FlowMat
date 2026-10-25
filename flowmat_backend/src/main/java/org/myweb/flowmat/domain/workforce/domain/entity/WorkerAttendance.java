package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One stretch a worker was at work in a project, from clocking in to clocking out (docs/architecture/adr/ADR-026-attendance.md,
 * V104). Rows are never deleted; a mistaken one is cancelled with a reason.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_attendance")
public class WorkerAttendance {
    @Id
    private String attendanceId;
    private String projectId;
    private String userId;
    private UUID requestId;
    /** The project date of the clock-in. */
    private LocalDate workDate;
    private OffsetDateTime clockIn;
    /** Null while still clocked in. */
    private OffsetDateTime clockOut;
    @Column(length = 200)
    private String note;
    private String recordedBy;
    private OffsetDateTime recordedAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String cancelledYn;
    private String cancelledBy;
    private OffsetDateTime cancelledAt;
    @Column(length = 500)
    private String cancelReason;

    public boolean isCancelled() {
        return "Y".equals(cancelledYn);
    }
}
