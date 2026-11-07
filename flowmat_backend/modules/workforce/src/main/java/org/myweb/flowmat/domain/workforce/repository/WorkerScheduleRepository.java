package org.myweb.flowmat.domain.workforce.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerScheduleRepository extends JpaRepository<WorkerSchedule, String> {
    List<WorkerSchedule> findAllByProjectIdAndUserId(String projectId, String userId);
    List<WorkerSchedule> findAllByProjectIdAndUserIdIn(String projectId, Collection<String> userIds);
    Optional<WorkerSchedule> findByScheduleIdAndProjectIdAndUserId(String scheduleId, String projectId, String userId);
}
