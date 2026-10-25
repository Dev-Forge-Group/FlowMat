package org.myweb.flowmat.domain.workforce.repository;

import java.util.Optional;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayHistory;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Search-only access; the latest-100 list keeps its own repository (as the version history search). */
public interface WorkerDayHistorySearchRepository extends Repository<WorkerDayHistory, String>, JpaSpecificationExecutor<WorkerDayHistory> {
    Optional<WorkerDayHistory> findByHistoryIdAndProjectIdAndUserId(String historyId, String projectId, String userId);
}
