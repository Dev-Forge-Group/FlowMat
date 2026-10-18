package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One version of a worker's weekly shifts and rota in a project (docs/architecture/adr/ADR-013-rota-history.md, V93): it
 * applies from {@code effectiveFrom} until the next version starts. Null is the version from the start, which the shifts
 * saved before V93 became. One version a day.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_schedule")
public class WorkerSchedule {
    @Id
    private String scheduleId;
    private String projectId;
    private String userId;
    private LocalDate effectiveFrom;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
