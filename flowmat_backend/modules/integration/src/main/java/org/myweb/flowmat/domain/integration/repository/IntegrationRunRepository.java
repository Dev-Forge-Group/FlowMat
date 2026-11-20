package org.myweb.flowmat.domain.integration.repository;

import java.util.List;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationRunRepository extends JpaRepository<IntegrationRun, String> {

    List<IntegrationRun> findTop20ByJobIdOrderByStartedAtDesc(String jobId);

    boolean existsByJobIdAndStatus(String jobId, String status);
}
