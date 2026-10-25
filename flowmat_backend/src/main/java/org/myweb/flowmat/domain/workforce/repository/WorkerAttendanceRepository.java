package org.myweb.flowmat.domain.workforce.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkerAttendanceRepository extends JpaRepository<WorkerAttendance, String> {
    Optional<WorkerAttendance> findByProjectIdAndUserIdAndRequestId(String projectId, String userId, UUID requestId);

    Optional<WorkerAttendance> findByAttendanceIdAndProjectIdAndUserId(String attendanceId, String projectId, String userId);

    List<WorkerAttendance> findAllByProjectIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAscAttendanceIdAsc(
        String projectId, String userId, OffsetDateTime from, OffsetDateTime to);

    Optional<WorkerAttendance> findFirstByProjectIdAndUserIdAndCancelledYnAndClockOutIsNullOrderByClockInDesc(
        String projectId, String userId, String cancelledYn);

    /** Lines that count and share time with [start, end); an open line runs on without end. */
    @Query("select a from WorkerAttendance a where a.projectId = :projectId and a.userId = :userId and a.cancelledYn = 'N' "
        + "and a.clockIn < :end and (a.clockOut is null or a.clockOut > :start)")
    List<WorkerAttendance> findOverlapping(@Param("projectId") String projectId, @Param("userId") String userId,
                                           @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** Holds a lock on a key until the transaction ends, so two records of one worker's attendance take turns. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockKey(@Param("key") String key);
}
