package org.myweb.flowmat.domain.workforce.repository;

import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerScheduleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerScheduleHistoryRepository extends JpaRepository<WorkerScheduleHistory, String> {
    List<WorkerScheduleHistory> findTop100ByProjectIdAndUserIdOrderBySavedAtDescHistoryIdDesc(String projectId, String userId);
}
