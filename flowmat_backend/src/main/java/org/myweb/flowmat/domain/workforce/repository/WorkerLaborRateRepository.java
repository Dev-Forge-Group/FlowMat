package org.myweb.flowmat.domain.workforce.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerLaborRate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerLaborRateRepository extends JpaRepository<WorkerLaborRate, String> {
    List<WorkerLaborRate> findAllByProjectIdAndUserIdOrderBySetAtDescRateIdDesc(String projectId, String userId);

    List<WorkerLaborRate> findAllByProjectIdAndUserIdInAndRemovedYn(String projectId, Collection<String> userIds, String removedYn);
}
