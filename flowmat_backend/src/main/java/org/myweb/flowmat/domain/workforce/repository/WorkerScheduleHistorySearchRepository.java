package org.myweb.flowmat.domain.workforce.repository;

import java.util.Optional;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerScheduleHistory;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/** Search-only access; retaining the existing repository keeps its latest-100 API unchanged. */
public interface WorkerScheduleHistorySearchRepository extends Repository<WorkerScheduleHistory, String>, JpaSpecificationExecutor<WorkerScheduleHistory> {
    Optional<WorkerScheduleHistory> findByHistoryIdAndProjectIdAndUserId(String historyId, String projectId, String userId);
}
