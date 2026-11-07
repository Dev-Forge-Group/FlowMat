package org.myweb.flowmat.domain.flowrun.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlowRunStepRepository extends JpaRepository<FlowRunStep, String> {
    List<FlowRunStep> findAllByFlowRunIdOrderBySequenceNoAsc(String flowRunId);
    Optional<FlowRunStep> findByStepIdAndFlowRunId(String stepId, String flowRunId);

    /** A run's step for the node; a Join has one, made on its first arrival (ADR-007). */
    Optional<FlowRunStep> findFirstByFlowRunIdAndNodeIdOrderBySequenceNoAsc(String flowRunId, String nodeId);
    boolean existsByFlowRunIdAndStatusNot(String flowRunId, String status);
    boolean existsByFlowRunIdAndStatusNotIn(String flowRunId, Set<String> statuses);
    long countByFlowRunId(String flowRunId);

    /** Running steps of a node in the open generic graph runs of a revision (docs/domain/flow-run-execution-policy.md EP6). */
    @Query("select count(step) from FlowRunStep step, FlowRun run where run.flowRunId = step.flowRunId"
        + " and run.workflowRevisionId = :revisionId and run.status = 'running' and run.executionMode = 'graph'"
        + " and run.productionRunId is null and step.nodeId = :nodeId and step.status = 'running'")
    long countRunningGraphSteps(@Param("revisionId") String revisionId, @Param("nodeId") String nodeId);
}
