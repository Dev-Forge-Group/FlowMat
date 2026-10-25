package org.myweb.flowmat.domain.workforce.repository;

import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerDayHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerDayHistoryRepository extends JpaRepository<WorkerDayHistory, String> {
    List<WorkerDayHistory> findTop100ByProjectIdAndUserIdOrderBySavedAtDescHistoryIdDesc(String projectId, String userId);
}
