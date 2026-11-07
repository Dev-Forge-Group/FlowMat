package org.myweb.flowmat.domain.flowrun.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowRunRepository extends JpaRepository<FlowRun, String> {

    List<FlowRun> findAllByWorkflowIdOrderByStartedAtDesc(String workflowId);

    Optional<FlowRun> findByProductionRunId(String productionRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from FlowRun run where run.flowRunId = :flowRunId")
    Optional<FlowRun> findLockedByFlowRunId(@Param("flowRunId") String flowRunId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from FlowRun run where run.productionRunId = :productionRunId")
    Optional<FlowRun> findLockedByProductionRunId(@Param("productionRunId") String productionRunId);
}
