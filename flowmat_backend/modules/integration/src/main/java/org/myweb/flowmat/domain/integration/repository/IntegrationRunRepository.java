package org.myweb.flowmat.domain.integration.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, String> {

    List<IntegrationRun> findTop20ByJobIdOrderByStartedAtDesc(String jobId);

    Optional<IntegrationRun> findByJobIdAndStatus(String jobId, String status);

    boolean existsByJobIdAndScheduledFor(String jobId, OffsetDateTime scheduledFor);
}
