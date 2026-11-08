package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** Days a worker is off, both included (docs/domain/worker-calendar.md WC3, V80): no shift of theirs starts on them. */
@Getter
@Setter
@Entity
@Table(name = "worker_day_off")
public class WorkerDayOff {

    @Id
    private String dayOffId;

    private String projectId;
    private String userId;
    private LocalDate firstDay;
    private LocalDate lastDay;
    private String reason;
    private String createdBy;
    private OffsetDateTime createdAt;
}
