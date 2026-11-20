package org.myweb.flowmat.domain.integration.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationJobRepository extends JpaRepository<IntegrationJob, String> {

    List<IntegrationJob> findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(String projectId, String deletedYn);

    Optional<IntegrationJob> findByJobIdAndProjectIdAndDeletedYn(String jobId, String projectId, String deletedYn);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from IntegrationJob job where job.jobId = :jobId and job.projectId = :projectId and job.deletedYn = 'N'")
    Optional<IntegrationJob> lock(@Param("jobId") String jobId, @Param("projectId") String projectId);

    /**
     * Scheduled jobs whose time has come, locked for the caller's transaction. Jobs another server holds are passed
     * over, and once it moves their next time on they are no longer due.
     */
    @Query(value = "select * from integration_job where schedule_enabled and deleted_yn = 'N' and next_run_at <= :now "
        + "order by next_run_at limit :limit for update skip locked", nativeQuery = true)
    List<IntegrationJob> lockDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
