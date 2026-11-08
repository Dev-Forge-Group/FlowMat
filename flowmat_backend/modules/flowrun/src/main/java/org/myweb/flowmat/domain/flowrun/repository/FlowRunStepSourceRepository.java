package org.myweb.flowmat.domain.flowrun.repository;

import java.util.List;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowRunStepSourceRepository extends JpaRepository<FlowRunStepSource, String> {
    List<FlowRunStepSource> findAllByStepIdOrderByArrivalNoAsc(String stepId);

    List<FlowRunStepSource> findAllByFlowRunIdOrderByStepIdAscArrivalNoAsc(String flowRunId);

    long countByStepId(String stepId);
}
