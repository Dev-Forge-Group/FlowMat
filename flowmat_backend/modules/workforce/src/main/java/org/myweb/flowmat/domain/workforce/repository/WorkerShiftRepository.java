package org.myweb.flowmat.domain.workforce.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkerShiftRepository extends JpaRepository<WorkerShift, String> {

    List<WorkerShift> findAllByProjectIdAndUserIdOrderByShiftStartAscWorkerShiftIdAsc(String projectId, String userId);

    List<WorkerShift> findAllByProjectIdAndUserIdIn(String projectId, Collection<String> userIds);
    List<WorkerShift> findAllByScheduleIdOrderByShiftStartAscWorkerShiftIdAsc(String scheduleId);

    /** Holds a lock on a key until the transaction ends, so two changes of one worker's shifts take turns. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
