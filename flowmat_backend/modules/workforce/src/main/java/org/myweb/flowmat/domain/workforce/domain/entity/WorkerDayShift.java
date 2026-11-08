package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * A worker's shifts for one date in place of their weekly ones (docs/domain/worker-calendar.md WC29, V82): "06:00-14:00,
 * 14:00-18:00", or empty when they do not work that day.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_day_shift")
public class WorkerDayShift {

    @Id
    private String dayShiftId;

    private String projectId;
    private String userId;
    private LocalDate workDate;
    private String shifts;
    private String reason;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
