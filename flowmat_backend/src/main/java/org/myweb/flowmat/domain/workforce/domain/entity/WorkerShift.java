package org.myweb.flowmat.domain.workforce.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One weekly shift of a worker in a project (docs/domain/worker-calendar.md WC2, V80). An end at or before the start runs
 * into the next day; equal times are a whole day, as for equipment.
 */
@Getter
@Setter
@Entity
@Table(name = "worker_shift")
public class WorkerShift {

    @Id
    private String workerShiftId;

    private String projectId;
    private String userId;
    private LocalTime shiftStart;
    private LocalTime shiftEnd;
    /** ISO days of the week it starts on, such as "1,2,3,4,5". */
    private String workDays;
    /** The week of the worker's rota it runs in; 1 without a rota (V87, WC44). */
    private int rotaWeek = 1;
    /** The version of the worker's weekly shifts it belongs to (V93, docs/architecture/adr/ADR-013-rota-history.md). */
    private String scheduleId;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
