package org.myweb.flowmat.domain.workforce.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerRota;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRotaRepository extends JpaRepository<WorkerRota, String> {

    Optional<WorkerRota> findByScheduleId(String scheduleId);

    List<WorkerRota> findAllByProjectIdAndUserIdIn(String projectId, Collection<String> userIds);
}
