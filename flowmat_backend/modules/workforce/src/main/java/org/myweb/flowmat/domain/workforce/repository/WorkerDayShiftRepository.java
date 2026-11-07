package org.myweb.flowmat.domain.workforce.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayShift;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerDayShiftRepository extends JpaRepository<WorkerDayShift, String> {

    /** The workers' own dates in [first, last]. */
    List<WorkerDayShift> findAllByProjectIdAndUserIdInAndWorkDateBetween(String projectId, Collection<String> userIds,
        LocalDate first, LocalDate last);

    List<WorkerDayShift> findAllByProjectIdAndUserIdAndWorkDateGreaterThanEqualOrderByWorkDateAsc(String projectId, String userId,
        LocalDate from);

    /** All of the worker's own dates, earliest first. */
    List<WorkerDayShift> findAllByProjectIdAndUserIdOrderByWorkDateAsc(String projectId, String userId);
}
